package com.agentx.tracer.service;

import com.agentx.ai.core.model.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中的流式回答注册表（内存版，deploy.mode=standalone 或未配置时生效）。
 * <p>
 * 执行与连接解耦：Agent 流注册后立即后台驱动（connect），HTTP 断开只取消
 * 前端自己的订阅，Agent 照常执行、事件照常进缓存；断点续传：每个事件带自增序号，
 * 前端重连时带 startSeq，先瞬时补发错过的事件，再接实时尾流。
 * 流终止（完成/出错/被 stop 中断）时从注册表摘除，可被 GC。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "deploy.mode", havingValue = "standalone", matchIfMissing = true)
public class InMemoryLiveStreamRegistry implements LiveStreamRegistry {

    /**
     * 一条进行中的流：可重放的 SSE 流（事件已带序号）+ 属主用户（恢复时校验权限）。
     */
    private record LiveStream(ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay,
                              Long ownerUserId) {
    }

    private final Map<String, LiveStream> live = new ConcurrentHashMap<>();

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> register(String conversationId, Long ownerUserId,
                                                            Flux<AgentStreamEvent> source) {
        AtomicReference<LiveStream> self = new AtomicReference<>();
        ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay = source
                .index((i, evt) -> ServerSentEvent.builder(evt).id(String.valueOf(i + 1)).build())
                .doFinally(signal -> {
                    LiveStream entry = self.get();
                    if (entry != null) {
                        // 按 key+value 摘除：同一会话紧接着开下一轮时，不会误删新注册的流
                        live.remove(conversationId, entry);
                        log.info("[agentx-console] 流式回答结束摘除: convId={}, signal={}", conversationId, signal);
                    }
                })
                .replay();
        LiveStream entry = new LiveStream(replay, ownerUserId);
        self.set(entry);
        live.put(conversationId, entry);
        replay.connect();
        return replay;
    }

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> attach(String conversationId, long startSeq) {
        LiveStream entry = live.get(conversationId);
        if (entry == null) {
            return null;
        }
        long from = Math.max(startSeq, 1);
        return entry.replay().skipUntil(sse -> Long.parseLong(sse.id()) >= from);
    }

    @Override
    public boolean isActive(String conversationId) {
        return live.containsKey(conversationId);
    }

    @Override
    public java.util.Set<String> activeConversationIds() {
        return java.util.Set.copyOf(live.keySet());
    }

    @Override
    public Long ownerOf(String conversationId) {
        LiveStream entry = live.get(conversationId);
        return entry == null ? null : entry.ownerUserId();
    }
}
