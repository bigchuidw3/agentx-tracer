package com.agentx.tracer.domain.vo;

import java.time.LocalDateTime;

/**
 * 会话列表项（侧栏展示）。
 *
 * @param conversationId 会话 id
 * @param title          会话标题（取首轮问题，超长截断）
 * @param messageCount   该会话的问答轮数
 * @param createdAt      首轮创建时间
 * @param lastActiveAt   末轮创建时间
 * @param streaming      该会话是否有进行中的流式回答（侧栏"生成中"标记）
 */
public record ConversationVO(String conversationId,
                             String title,
                             int messageCount,
                             LocalDateTime createdAt,
                             LocalDateTime lastActiveAt,
                             boolean streaming) {
}
