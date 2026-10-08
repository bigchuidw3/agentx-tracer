package com.agentx.tracer.chat;

import com.agentx.tracer.domain.entities.LlmModel;
import com.agentx.tracer.mapper.LlmModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.DefaultAddressResolverGroup;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态模型供给实现：模型配置（llm_model 表）保存即生效，无兜底。
 *
 * <p>未配置模型时 {@link #getChatModel()} 返回 null，
 * 对话入口（AgentController）负责拦截并提示先去「模型配置」。
 *
 * <p>统一开启 {@code streamUsage}（stream_options.include_usage），
 * 保证流式响应最后一帧携带 prompt/completion tokens，供 Token 计量消费。
 *
 * <p>内网自签 HTTPS 端点（如 https://IP:7443）信任所有证书；
 * 300s 响应超时适配长流式输出。
 *
 * @author agentx-console
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicChatModelProvider implements ChatModelProvider {

    /** 默认温度（llm_model 暂未存 temperature，统一取此值） */
    @Value("${app.model.temperature:0.7}")
    private double defaultTemperature;

    private final LlmModelMapper llmModelMapper;
    private final DataSource dataSource;

    /** 激活模型缓存（key=userId；模型管理读取单价/上下文窗口也用它） */
    private final Map<Long, LlmModel> activeModels = new ConcurrentHashMap<>();

    private final Map<Long, ChatModel> chatModels = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        ensureTemperatureColumn();
        ensureUserIdColumn();
        refreshAll();
    }

    /** 存量库补 temperature 列（幂等：列已存在则跳过） */
    private void ensureTemperatureColumn() {
        try {
            new JdbcTemplate(dataSource).execute(
                    "ALTER TABLE llm_model ADD COLUMN temperature DECIMAL(4,2) DEFAULT 0.7");
            log.info("[agentx-console] llm_model 已补 temperature 列");
        } catch (Exception e) {
            log.debug("[agentx-console] temperature 列跳过（可能已存在）: {}", e.getMessage());
        }
    }

    /** 存量库补 user_id 列（幂等：列已存在则跳过） */
    private void ensureUserIdColumn() {
        try {
            new JdbcTemplate(dataSource).execute(
                    "ALTER TABLE llm_model ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0");
            log.info("[agentx-console] llm_model 已补 user_id 列");
        } catch (Exception e) {
            log.debug("[agentx-console] user_id 列跳过（可能已存在）: {}", e.getMessage());
        }
    }

    /** 启动时一次性加载全部启用模型（按 user_id 建缓存）。 */
    private void refreshAll() {
        List<LlmModel> all = llmModelMapper.selectList(
                new LambdaQueryWrapper<LlmModel>().eq(LlmModel::getEnabled, 1));
        activeModels.clear();
        chatModels.clear();
        for (LlmModel m : all) {
            putModel(m);
        }
        log.info("[agentx-console] 已加载 {} 个用户模型", all.size());
    }

    @Override
    public void refresh(Long userId) {
        long uid = userId;
        LlmModel active = llmModelMapper.selectOne(
                new LambdaQueryWrapper<LlmModel>()
                        .eq(LlmModel::getUserId, uid)
                        .eq(LlmModel::getEnabled, 1)
                        .orderByAsc(LlmModel::getId)
                        .last("LIMIT 1"));
        if (active == null) {
            activeModels.remove(uid);
            chatModels.remove(uid);
            return;
        }
        putModel(active);
    }

    private void putModel(LlmModel active) {
        long uid = active.getUserId() == null ? 0L : active.getUserId();
        double temperature = active.getTemperature() == null
                ? defaultTemperature : active.getTemperature().doubleValue();
        activeModels.put(uid, active);
        chatModels.put(uid, build(active.getBaseUrl(), active.getApiKey(),
                active.getModelName(), temperature));
        log.info("[agentx-console] ChatModel 构建完成: userId={}, model={}", uid, active.getModelName());
    }

    @Override
    public ChatModel getChatModel(Long userId) {
        long uid = userId;
        ChatModel cm = chatModels.get(uid);
        if (cm == null) {
            refresh(uid);
            cm = chatModels.get(uid);
        }
        return cm;
    }

    /**
     * 指定用户的激活模型配置（含单价/上下文窗口）；未配置返回 null。
     */
    public LlmModel getActiveModel(Long userId) {
        long uid = userId;
        return activeModels.get(uid);
    }

    /**
     * 兼容：返回任一激活模型（成本折算/统计在用户未明确时兜底用）。
     */
    public LlmModel getActiveModel() {
        return activeModels.values().stream().findFirst().orElse(null);
    }

    /**
     * 构建 OpenAI 兼容 ChatModel。
     */
    private ChatModel build(String baseUrl, String apiKey, String model, double temperature) {
        HttpClient httpClient = HttpClient.create()
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(Duration.ofSeconds(300))
                // 内网自签证书端点：信任所有证书
                .secure(ssl -> {
                    try {
                        ssl.sslContext(SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE).build());
                    } catch (Exception e) {
                        throw new IllegalStateException("构建 SSL 上下文失败", e);
                    }
                });

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                .webClientBuilder(WebClient.builder()
                        .clientConnector(new ReactorClientHttpConnector(httpClient)))
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .streamUsage(true)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();
    }
}
