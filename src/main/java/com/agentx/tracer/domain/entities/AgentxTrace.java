package com.agentx.tracer.domain.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_trace 表实体（只读，写入走框架 TraceStore）。
 *
 * <p>每轮 LLM 调用一行：input_data 为完整请求 JSON（messages + tools schema 等），
 * output_data 为模型输出（工具轮是 tool_calls JSON，最终轮是纯文本报告）。
 * 消息历史逐轮累积：第 N 轮 input 的 messages 包含前 N-1 轮全部内容，越往后单轮记录越大。
 * 失败的调用只有本表有记录（失败轮不产生 assistant 消息，agentx_session 看不到）。
 */
@Data
@TableName("agentx_trace")
public class AgentxTrace implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 本次调用ID（与 agentx_conversation.session_id / agentx_session.session_id 同值） */
    @TableField("session_id")
    private Long sessionId;

    @TableField("conversation_id")
    private String conversationId;

    /** 用户ID（数据隔离用） */
    @TableField("user_id")
    private String userId;

    /** 本轮 ReAct 循环轮次，每个 session 从 1 重新计数；工具 Span = 发起调用的 LLM 轮次 */
    private Integer round;

    /** Span 类型：LLM | TOOL */
    @TableField("span_type")
    private String spanType;

    /** 工具名（仅 TOOL Span） */
    @TableField("tool_name")
    private String toolName;

    /** 模型 tool_calls 的调用ID（仅 TOOL Span） */
    @TableField("tool_call_id")
    private String toolCallId;

    /** LLM: 完整请求 JSON 原文 / TOOL: 工具入参 JSON */
    @TableField("input_data")
    private String inputData;

    /** 模型输出原文（工具轮是 tool_calls JSON，最终轮是纯文本报告） */
    @TableField("output_data")
    private String outputData;

    private String think;

    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("duration_ms")
    private Long durationMs;

    /** 是否成功：1 成功 0 失败 */
    private Integer success;

    @TableField("error_message")
    private String errorMessage;

    private LocalDateTime createdAt;
}
