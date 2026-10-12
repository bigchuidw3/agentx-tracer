package com.agentx.tracer.chat;

import org.springframework.ai.chat.model.ChatModel;

/**
 * ChatModel 供给接口。
 *
 * <p>抽象模型接入方式：实现方决定配置来源（数据库激活模型 / yml 兜底），
 * 模型管理保存/切换激活后调用 {@link #refresh()} 热重建，无需重启服务。
 *
 * @author agentx-console
 */
public interface ChatModelProvider {

    /**
     * 获取指定用户生效的 ChatModel 实例（用户隔离：每人独立模型配置）。
     */
    ChatModel getChatModel(Long userId);

    /**
     * 重建指定用户的 ChatModel（该用户模型配置变更后调用）。
     */
    void refresh(Long userId);

    /**
     * 指定用户的模型是否已配置且可用（未配置时对话入口应拦截并提示）。
     */
    default boolean isAvailable(Long userId) {
        return getChatModel(userId) != null;
    }

    /**
     * 指定用户激活模型的上下文窗口大小；未配置或不支持时返回 null。
     * <p>供上下文压缩阈值自适应（tokenThreshold 按窗口比例计算）使用。
     * 默认实现返回 null，由实现方按需覆盖，保证接口向后兼容。
     */
    default Integer contextWindow(Long userId) {
        return null;
    }
}
