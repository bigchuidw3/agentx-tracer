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
 * agentx_session 表实体。
 *
 * <p>v1.0.1 表结构：按 conversation_id + state_key 组织，每条消息一行，
 * item_index 保留顺序，state_data 为 MessageJsonSerializer 序列化的消息 JSON。
 */
@Data
@TableName("agentx_session")
public class AgentxSession implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("session_id")
    private String sessionId;

    /** 用户ID（数据隔离用） */
    @TableField("user_id")
    private String userId;

    @TableField("state_key")
    private String stateKey;

    @TableField("item_index")
    private Integer itemIndex;

    @TableField("state_data")
    private String stateData;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
