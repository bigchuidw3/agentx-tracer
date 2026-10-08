package com.agentx.tracer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局 OTel/Opik 导出配置（内置，所有用户共用）。
 * 读取 {@code management.otlp.tracing.*}（对齐 参考实现），
 * header 里的 Authorization / Comet-Workspace / projectName 分别映射 token / workspace / 项目名。
 */
@Data
@Component
@ConfigurationProperties(prefix = "management.otlp.tracing")
public class OtlpTracingProperties {

    private String endpoint = "";
    private Map<String, String> headers = new HashMap<>();

    public String token() {
        return headers.getOrDefault("Authorization", "");
    }

    public String workspace() {
        return headers.getOrDefault("Comet-Workspace", "");
    }

    public String projectName() {
        return headers.getOrDefault("projectName", "");
    }
}
