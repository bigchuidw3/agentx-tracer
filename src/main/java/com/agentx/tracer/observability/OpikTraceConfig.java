package com.agentx.tracer.observability;

import com.agentx.tracer.config.OtlpTracingProperties;
import com.agentx.tracer.service.OpikConfigService;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 注册内置的全局 OTLP 导出器（带用户开启过滤）+ 自定义 SdkTracerProvider（UUID traceId）。
 *
 * <p>traceId 用 UUID 生成（32 位 hex，去掉连字符），保证 Opik / Langfuse 等平台能正确解析。
 * micrometer {@code Tracer} 由 Spring Boot 基于本 {@link SdkTracerProvider} 自动装配。
 *
 * @author agentx-console
 */
@Configuration
public class OpikTraceConfig {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final IdGenerator UUID_ID_GENERATOR = new IdGenerator() {
        @Override
        public String generateSpanId() {
            byte[] b = new byte[8];
            RANDOM.nextBytes(b);
            return HexFormat.of().formatHex(b);
        }

        @Override
        public String generateTraceId() {
            return UUID.randomUUID().toString().replace("-", "");
        }
    };

    @Bean
    public SpanExporter opikSpanExporter(OpikConfigService opikConfigService, OtlpTracingProperties props) {
        var builder = OtlpHttpSpanExporter.builder().setEndpoint(props.getEndpoint());
        if (!props.token().isBlank()) {
            builder.addHeader("Authorization", props.token());
        }
        if (!props.workspace().isBlank()) {
            builder.addHeader("Comet-Workspace", props.workspace());
        }
        if (!props.projectName().isBlank()) {
            builder.addHeader("projectName", props.projectName());
        }
        return new OpikEnabledSpanExporter(opikConfigService, builder.build());
    }

    @Bean
    public SdkTracerProvider sdkTracerProvider(SpanExporter opikSpanExporter) {
        return SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(opikSpanExporter))
                .setIdGenerator(UUID_ID_GENERATOR)
                .build();
    }
}
