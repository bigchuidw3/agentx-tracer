package com.agentx.tracer.domain.vo;

import java.util.List;

/**
 * 会话列表分页响应。
 *
 * @param conversations 当前页的会话列表
 * @param total         全部会话总数（不是消息轮数）
 * @param page          当前页码（0-based）
 * @param size          每页大小
 * @param hasMore       是否还有更多
 */
public record ConversationPage(List<ConversationVO> conversations,
                               int total,
                               int page,
                               int size,
                               boolean hasMore) {

    public static ConversationPage empty(int page, int size) {
        return new ConversationPage(List.of(), 0, page, size, false);
    }
}
