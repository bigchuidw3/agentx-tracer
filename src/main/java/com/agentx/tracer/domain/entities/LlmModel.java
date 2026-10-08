package com.agentx.tracer.domain.entities;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * llm_model 表实体：模型接入配置（OpenAI 兼容端点 + 计量单价 + 上下文窗口）。
 *
 * <p>实际运行使用 enabled=1 的激活模型构建 ChatModel；
 * input_price/output_price 用于 Token 成本折算，context_window 用于统计页上下文占用进度条。
 */
@Data
@TableName("llm_model")
public class LlmModel implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 展示名 */
    private String name;

    /** OpenAI 兼容端点 */
    @TableField("base_url")
    private String baseUrl;

    @TableField("api_key")
    private String apiKey;

    /** 模型标识，如 qwen-plus / XinCube */
    @TableField("model_name")
    private String modelName;

    private Integer enabled;

    /** 归属用户 id（每个用户独立一份模型配置） */
    @TableField("user_id")
    private Long userId;

    /** 元/百万输入 token */
    @TableField("input_price")
    private BigDecimal inputPrice;

    /** 元/百万输出 token */
    @TableField("output_price")
    private BigDecimal outputPrice;

    /** 上下文窗口大小（统计页进度条用） */
    @TableField("context_window")
    private Integer contextWindow;

    /** 采样温度（0.0-2.0，默认 0.7） */
    @TableField("temperature")
    private BigDecimal temperature;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
