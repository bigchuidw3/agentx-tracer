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
 * agentx_conversation 表实体。
 *
 * <p>v1.0.1 新增：每次 Agent 调用一行，记录调用边界与执行状态。
 */
@Data
@TableName("agentx_conversation")
public class AgentxConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private String userId;

    private String question;

    private String status;

    private LocalDateTime createdAt;

    @TableField("completed_at")
    private LocalDateTime completedAt;

    // ==================== M2 观测汇总列（终态时由框架写入） ====================

    /** 端到端总耗时（毫秒，同 session 恢复场景累加） */
    @TableField("duration_ms")
    private Long durationMs;

    /** ReAct 轮次（LLM 推理轮数） */
    private Integer rounds;

    /** 工具执行总次数 */
    @TableField("tool_calls")
    private Integer toolCalls;

    /** 失败工具次数 */
    @TableField("tool_failures")
    private Integer toolFailures;

    /** 输入 token 累计 */
    @TableField("prompt_tokens")
    private Long promptTokens;

    /** 输出 token 累计 */
    @TableField("completion_tokens")
    private Long completionTokens;

    /** prompt + completion 合计 */
    @TableField("total_tokens")
    private Long totalTokens;

    /** 实际使用的模型名（最后一次响应 metadata.getModel()） */
    @TableField("model_name")
    private String modelName;
}
