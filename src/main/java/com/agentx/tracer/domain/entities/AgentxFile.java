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
 * agentx_file 表实体。
 */
@Data
@TableName("agentx_file")
public class AgentxFile implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务唯一键 UUID
     */
    @TableField("file_id")
    private String fileId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("minio_path")
    private String minioPath;

    /**
     * 解析后文本（截断版），用于 LLM 直接加载
     */
    @TableField("extracted_text")
    private String extractedText;

    /**
     * PROCESSING / SUCCESS / FAILED
     */
    @TableField("status")
    private String status;

    /**
     * 0=未向量化（直接加载完整文本） 1=已向量化（走 RAG 检索）
     */
    @TableField("embed")
    private Integer embed;

    @TableField("conversation_id")
    private String conversationId;

    /**
     * 关联到 agentx_session.id（一次问答轮的主键），用于历史回放时按轮次取附件
     */
    @TableField("session_id")
    private Long sessionId;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
