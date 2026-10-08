package com.agentx.tracer.domain.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * agentx_skill 表实体。
 */
@Data
@TableName("agentx_skill")
public class AgentxSkill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * skill 目录名（唯一键）
     */
    private String name;

    /**
     * 归属用户：0=平台内置，>0=用户私有
     */
    @TableField("user_id")
    private Long userId;

    /**
     * skill 目录绝对路径
     */
    private String skillPath;

    /**
     * skill 描述（SKILL.md frontmatter 的 description）
     */
    private String description;

    /**
     * 0=禁用 1=启用
     */
    private Integer enabled;

    /**
     * 上传的原始 zip 文件名
     */
    private String fileName;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
