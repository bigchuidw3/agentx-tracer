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
 * agentx_alert 表实体：预算告警流水。
 *
 * <p>每次调用成本落库后累计当月总额，越过阈值（默认 80% / 100%）生成一条告警。
 */
@Data
@TableName("agentx_alert")
public class AgentxAlert implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 告警类型：BUDGET_80 / BUDGET_100 */
    @TableField("alert_type")
    private String alertType;

    /** 触发告警的最后一次调用 */
    @TableField("conversation_id")
    private String conversationId;

    private String detail;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
