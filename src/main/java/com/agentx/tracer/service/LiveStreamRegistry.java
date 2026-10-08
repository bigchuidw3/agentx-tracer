package com.agentx.tracer.service;

import com.agentx.ai.core.model.AgentStreamEvent;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * 进行中的流式回答注册表（key = conversationId）。
 * <p>
 * 两套实现，配置 deploy.mode 切换：
 * <ul>
 *   <li>standalone（默认）：JVM 内存版，replay 缓存 + 后台驱动，单实例部署</li>
 *   <li>cluster：内存流 + 事件异步写 Redis Stream，任意实例可续传，多实例部署</li>
 * </ul>
 * <p>
 * 两个职责：
 * <ul>
 *   <li>执行与连接解耦：Agent 流注册后立即后台驱动，HTTP 断开不影响执行</li>
 *   <li>断点续传：每个事件带自增序号（SSE 的 id 字段），前端重连时带 startSeq 补发续传</li>
 * </ul>
 */
public interface LiveStreamRegistry {

    /**
     * 注册并立即后台驱动。
     *
     * @return 给当前前端订阅的流（晚订阅者会先收到历史缓存）
     */
    Flux<ServerSentEvent<AgentStreamEvent>> register(String conversationId, Long ownerUserId,
                                                     Flux<AgentStreamEvent> source);

    /**
     * 接回进行中的流：先补发序号 >= startSeq 的缓存事件，再续实时尾流。
     *
     * @return 没有进行中的流时返回 null
     */
    Flux<ServerSentEvent<AgentStreamEvent>> attach(String conversationId, long startSeq);

    /**
     * 指定会话是否有进行中的流。
     */
    boolean isActive(String conversationId);

    /**
     * 全部进行中流的会话 ID（列表接口批量标记用，一次查询避免逐会话往返）。
     */
    java.util.Set<String> activeConversationIds();

    /**
     * 进行中流的属主用户（无进行中流时为 null）。
     */
    Long ownerOf(String conversationId);
}
