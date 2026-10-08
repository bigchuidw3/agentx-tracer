package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.chat.ChatModelProvider;
import com.agentx.tracer.common.R;
import com.agentx.tracer.domain.entities.LlmModel;
import com.agentx.tracer.mapper.LlmModelMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理：配置 OpenAI 兼容端点 + 计量单价 + 上下文窗口，保存后热重建 ChatModel。
 *
 * <p>连通性测试用表单参数临时构建客户端发一次最小请求，顺带验证 usage 是否返回
 * （决定 Token 计量是否可用）。
 */
@Slf4j
@RestController
@RequestMapping("/model")
@RequiredArgsConstructor
@SaCheckLogin
public class ModelAdminController {

    private final LlmModelMapper llmModelMapper;
    private final ChatModelProvider chatModelProvider;
    private final AuthService authService;

    /** 模型表单（保存即生效，无激活动作） */
    @Data
    public static class ModelForm {
        private Long id;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private String inputPrice;
        private String outputPrice;
        private Integer contextWindow;
        private String temperature;
    }

    /**
     * 模型列表（key 脱敏展示）。
     */
    @GetMapping
    public R<List<LlmModel>> list() {
        long userId = requireUserId();
        List<LlmModel> models = llmModelMapper.selectList(
                new LambdaQueryWrapper<LlmModel>()
                        .eq(LlmModel::getUserId, userId)
                        .orderByAsc(LlmModel::getId));
        models.forEach(m -> m.setApiKey(maskKey(m.getApiKey())));
        return R.ok(models);
    }

    /**
     * 保存（新增或更新）：保存后热重建 ChatModel。
     */
    @PostMapping
    public R<LlmModel> save(@RequestBody ModelForm form) {
        if (form.getBaseUrl() == null || form.getBaseUrl().isBlank()) {
            throw new IllegalArgumentException("Base URL 不能为空");
        }
        if (form.getModelName() == null || form.getModelName().isBlank()) {
            throw new IllegalArgumentException("模型名不能为空");
        }

        long userId = requireUserId();
        LlmModel entity = form.getId() == null ? new LlmModel() : llmModelMapper.selectById(form.getId());
        if (entity == null) {
            throw new IllegalArgumentException("模型不存在: " + form.getId());
        }
        if (form.getId() != null && entity.getUserId() != null && entity.getUserId() != userId) {
            throw new IllegalArgumentException("无权操作他人模型");
        }
        entity.setUserId(userId);
        entity.setName(form.getModelName().trim());
        entity.setBaseUrl(form.getBaseUrl().trim());
        // key 脱敏回传时不动原值：只有传了非掩码形态才更新
        if (form.getApiKey() != null && !form.getApiKey().isBlank() && !form.getApiKey().contains("****")) {
            entity.setApiKey(form.getApiKey().trim());
        }
        entity.setModelName(form.getModelName().trim());
        entity.setEnabled(1);
        entity.setInputPrice(parseDecimal(form.getInputPrice(), entity.getInputPrice()));
        entity.setOutputPrice(parseDecimal(form.getOutputPrice(), entity.getOutputPrice()));
        entity.setContextWindow(form.getContextWindow());
        entity.setTemperature(parseDecimal(form.getTemperature(),
                entity.getTemperature() == null ? new java.math.BigDecimal("0.7") : entity.getTemperature()));
        entity.setUpdatedAt(LocalDateTime.now());

        if (form.getId() == null) {
            llmModelMapper.insert(entity);
        } else {
            llmModelMapper.updateById(entity);
        }
        // 热重建（仅当前用户）
        chatModelProvider.refresh(userId);
        entity.setApiKey(maskKey(entity.getApiKey()));
        return R.ok("保存成功，模型配置已生效", entity);
    }

    /**
     * 连通性测试：用表单参数临时构建客户端发最小请求，返回耗时 / 成功 / usage 是否返回。
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test(@RequestBody ModelForm form) {
        Map<String, Object> result = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try {
            String apiKey = resolveApiKey(form.getApiKey());
            ChatModel chatModel = buildTemp(form.getBaseUrl().trim(), apiKey, form.getModelName().trim());

            var response = chatModel.call(new Prompt("回复ok两个字母即可"));
            long cost = System.currentTimeMillis() - start;
            result.put("success", true);
            result.put("latencyMs", cost);
            String content = response.getResult() != null && response.getResult().getOutput() != null
                    ? String.valueOf(response.getResult().getOutput().getText()) : "";
            result.put("reply", content != null && content.length() > 50 ? content.substring(0, 50) : content);
            boolean hasUsage = response.getMetadata() != null && response.getMetadata().getUsage() != null
                    && response.getMetadata().getUsage().getTotalTokens() != null
                    && response.getMetadata().getUsage().getTotalTokens() > 0;
            result.put("usageReturned", hasUsage);
            return R.ok("连通成功", result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("latencyMs", System.currentTimeMillis() - start);
            result.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return R.ok("连通失败", result);
        }
    }

    // ======================================================================
    // 私有方法
    // ======================================================================

    /** 表单 key 若为掩码形态，取库里原 key 测试 */
    private String resolveApiKey(String formKey) {
        if (formKey != null && !formKey.isBlank() && !formKey.contains("****")) {
            return formKey.trim();
        }
        LlmModel active = llmModelMapper.selectOne(
                new LambdaQueryWrapper<LlmModel>()
                        .eq(LlmModel::getUserId, requireUserId())
                        .eq(LlmModel::getEnabled, 1)
                        .last("LIMIT 1"));
        if (active != null) {
            return active.getApiKey();
        }
        throw new IllegalArgumentException("API Key 未配置");
    }

    private long requireUserId() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new IllegalArgumentException("未登录");
        }
        return userId;
    }

    private ChatModel buildTemp(String baseUrl, String apiKey, String model) {
        try {
            SslContextBuilder sslBuilder = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE);
            HttpClient httpClient = HttpClient.create()
                    .resolver(DefaultAddressResolverGroup.INSTANCE)
                    .responseTimeout(Duration.ofSeconds(30))
                    .secure(ssl -> {
                        try {
                            ssl.sslContext(sslBuilder.build());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
            OpenAiApi api = OpenAiApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .restClientBuilder(RestClient.builder()
                            .requestFactory(new ReactorClientHttpRequestFactory(httpClient)))
                    .webClientBuilder(WebClient.builder()
                            .clientConnector(new ReactorClientHttpConnector(httpClient)))
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder().model(model).temperature(0.1).build())
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException("构建测试客户端失败: " + e.getMessage(), e);
        }
    }

    private java.math.BigDecimal parseDecimal(String value, java.math.BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new java.math.BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("单价格式错误: " + value);
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.length() <= 10) {
            return key;
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
