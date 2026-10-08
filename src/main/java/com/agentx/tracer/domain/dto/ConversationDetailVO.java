package com.agentx.tracer.domain.vo;

import java.util.List;

/**
 * 会话详情（点击历史会话后加载，包含所有轮次）。
 *
 * @param conversationId 会话 id
 * @param messages       按时间排序的问答轮次
 */
public record ConversationDetailVO(String conversationId,
                                   List<SessionMessageVO> messages) {
}
