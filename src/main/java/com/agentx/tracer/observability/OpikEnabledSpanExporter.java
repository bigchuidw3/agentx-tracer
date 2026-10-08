package com.agentx.tracer.observability;

import com.agentx.tracer.service.OpikConfigService;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按用户过滤的 Span 导出器：读取 span 上 {@code opik.metadata.userId} 属性（TracingHook 写入），
 * 仅当该用户开启了同步（enabled=true）时才转发给内置的全局 OTLP 导出器。
 *
 * @author agentx-console
 */
public class OpikEnabledSpanExporter implements SpanExporter {

    private static final AttributeKey<String> USER_ID_KEY = AttributeKey.stringKey("opik.metadata.userId");

    private final OpikConfigService opikConfigService;
    private final SpanExporter delegate;

    public OpikEnabledSpanExporter(OpikConfigService opikConfigService, SpanExporter delegate) {
        this.opikConfigService = opikConfigService;
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        Map<String, List<SpanData>> byUser = new LinkedHashMap<>();
        for (SpanData span : spans) {
            String userId = span.getAttributes().get(USER_ID_KEY);
            if (userId != null && !userId.isBlank()) {
                byUser.computeIfAbsent(userId, k -> new ArrayList<>()).add(span);
            }
        }
        List<SpanData> toExport = new ArrayList<>();
        for (Map.Entry<String, List<SpanData>> entry : byUser.entrySet()) {
            if (opikConfigService.isEnabled(entry.getKey())) {
                toExport.addAll(entry.getValue());
            }
        }
        return toExport.isEmpty() ? CompletableResultCode.ofSuccess() : delegate.export(toExport);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}
