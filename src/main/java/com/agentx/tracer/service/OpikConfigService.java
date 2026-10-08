package com.agentx.tracer.service;

import com.agentx.tracer.config.OtlpTracingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Opik 同步开关（按用户隔离，仅存 enabled）。
 *
 * <p>Opik 的 endpoint / token / workspace / projectName 为全局内置配置（见 {@link OtlpTracingProperties}），
 * 用户只在个人中心选择是否开启同步。复用 sys_config KV 表，key 带 userId 后缀。
 *
 * @author agentx-console
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpikConfigService {

    private final JdbcTemplate jdbcTemplate;
    private final OtlpTracingProperties otlpProperties;

    public Map<String, Object> get(Long userId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", isEnabled(String.valueOf(userId)));
        config.put("endpoint", otlpProperties.getEndpoint());
        config.put("workspace", otlpProperties.workspace());
        config.put("projectName", otlpProperties.projectName());
        return config;
    }

    public void save(Long userId, boolean enabled) {
        upsert(userId, "enabled", String.valueOf(enabled));
    }

    /**
     * 是否开启同步（供导出器按 span 上的 userId 过滤用）。
     */
    public boolean isEnabled(String userId) {
        return "true".equalsIgnoreCase(read(userId, "enabled"));
    }

    /**
     * 连通性测试：对全局内置端点发一次 POST（带鉴权 Header），只有 2xx 算通过。
     */
    public Map<String, Object> testConnection() {
        Map<String, Object> result = new LinkedHashMap<>();
        String endpoint = otlpProperties.getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            result.put("success", false);
            result.put("message", "未配置 OTel 地址");
            return result;
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"resourceSpans\":[]}"));
            addHeader(builder, "Authorization", otlpProperties.token());
            addHeader(builder, "Comet-Workspace", otlpProperties.workspace());
            addHeader(builder, "projectName", otlpProperties.projectName());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                result.put("success", true);
                result.put("message", "连接成功（HTTP " + status + "）");
            } else if (status == 401 || status == 403) {
                result.put("success", false);
                result.put("message", "鉴权未通过（HTTP " + status + "），请检查 Token / Workspace");
            } else {
                result.put("success", false);
                result.put("message", "连接失败（HTTP " + status + "）");
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "连接失败：" + e.getMessage());
        }
        return result;
    }

    private static void addHeader(HttpRequest.Builder builder, String name, String value) {
        if (value != null && !value.isBlank()) {
            builder.header(name, value);
        }
    }

    private String key(Long userId, String field) {
        return "opik." + field + "." + userId;
    }

    private String read(String userId, String field) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT cfg_value FROM sys_config WHERE cfg_key = ?", String.class,
                    "opik." + field + "." + userId);
        } catch (Exception e) {
            return null;
        }
    }

    private void upsert(Long userId, String field, String value) {
        String cfgKey = key(userId, field);
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_config WHERE cfg_key = ?", Integer.class, cfgKey);
            if (exists != null && exists > 0) {
                jdbcTemplate.update(
                        "UPDATE sys_config SET cfg_value = ? WHERE cfg_key = ?", value, cfgKey);
            } else {
                jdbcTemplate.update(
                        "INSERT INTO sys_config (cfg_key, cfg_value) VALUES (?, ?)", cfgKey, value);
            }
        } catch (Exception e) {
            log.warn("[agentx-console] 保存 Opik 配置失败: key={}, err={}", cfgKey, e.getMessage());
        }
    }
}
