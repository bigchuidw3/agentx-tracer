package com.agentx.tracer.domain.dto;

import java.util.List;

/**
 * /agent/stream 请求体。
 *
 * @param query          用户问题（recovery 恢复请求可为空，不新起执行）
 * @param conversationId 会话 id
 * @param online         是否开启联网搜索
 * @param fileIds        关联文档 id 列表
 * @param recovery       是否恢复请求：true 时不新起执行，接回该会话进行中的流（断线/重开后续传）
 * @param startSeq       恢复时从第几个事件开始收；null/0/1 表示从头全量补发，N 表示增量续传
 */
public record ChatRequest(
        String query,
        String conversationId,
        Boolean online,
        List<String> fileIds,
        Boolean recovery,
        Long startSeq
) {
}
