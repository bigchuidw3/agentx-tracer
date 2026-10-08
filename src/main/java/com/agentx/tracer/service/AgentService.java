package com.agentx.tracer.service;

import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import org.springframework.lang.Nullable;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 智能体服务：对外提供主智能体的流式调用、中断与恢复能力。
 *
 * <p>内部在每次请求时构建一个 {@code ReactAgent} 实例（工具/skills/会话上下文按请求装配），
 * 对调用方屏蔽装配细节。ChatModel 经 {@code ChatModelProvider} 接口供给，可替换实现。
 *
 * @author agentx-console
 */
public interface AgentService {

    /**
     * 流式调用主智能体。
     *
     * @param query   用户输入
     * @param params  调用参数（conversationId / userId 等）
     * @param fileIds 本次上传关联的文件 ID 列表（可为 null）
     */
    Flux<AgentStreamEvent> streamForResult(String query, RunnableParams params, List<String> fileIds);

    /**
     * 用户主动中断指定会话的流式任务，持久化快照以便后续恢复。
     */
    boolean interrupt(String conversationId);

    /**
     * 检查指定会话是否存在未恢复的中断状态。
     */
    boolean hasInterruptedState(String conversationId);

    /**
     * 丢弃指定会话的中断快照：新消息顶掉进行中的回答时调用。
     */
    void discardInterruptedState(String conversationId);

    /**
     * 从中断状态恢复流式执行。
     *
     * @param resumeQuery 用户中断后输入的澄清/继续语句（可为空，直接恢复不追加消息）
     */
    Flux<AgentStreamEvent> resumeStream(String conversationId, List<String> fileIds,
                                        @Nullable String resumeQuery);
}
