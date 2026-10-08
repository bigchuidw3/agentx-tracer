package com.agentx.tracer.mapper;

import com.agentx.tracer.domain.entities.AgentxTrace;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;

/**
 * agentx_trace 表 Mapper（只读）。
 */
@Mapper
public interface AgentxTraceMapper extends BaseMapper<AgentxTrace> {

    /**
     * 轮次概要：按窗口取全部轮次的结构化列，input 字符量用 CHAR_LENGTH 在库端计算，
     * 不加载 longtext 原文（大 trace 防内存放大）。
     */
    @Select("""
            SELECT session_id AS sessionId, round,
                   CHAR_LENGTH(input_data) AS inputChars,
                   success, error_message AS errorMessage
            FROM agentx_trace
            WHERE conversation_id = #{conversationId} AND user_id = #{userId}
            ORDER BY session_id, round
            """)
    List<TraceRoundSummary> selectRoundSummaries(@Param("conversationId") String conversationId,
                                                 @Param("userId") String userId);

    /**
     * 轮次概要行：只含结构化列，不含 input/output 原文。
     */
    @Data
    class TraceRoundSummary {
        private Long sessionId;
        private Integer round;
        private Long inputChars;
        private Integer success;
        private String errorMessage;
    }
}
