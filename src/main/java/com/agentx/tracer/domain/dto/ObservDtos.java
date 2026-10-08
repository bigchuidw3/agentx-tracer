package com.agentx.tracer.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 可观测模块 DTO 集合（调用列表 / 统计页签 / 轨迹页签）。
 */
public final class ObservDtos {

    private ObservDtos() {
    }

    /** 调用列表行 */
    @Data
    @Builder
    public static class CallVO {
        private Long id;
        private String sessionId;
        private String conversationId;
        private String userId;
        private String questionExcerpt;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private Long durationMs;
        private Integer rounds;
        private Integer toolCalls;
        private Integer toolFailures;
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private BigDecimal cost;
        private String modelName;
    }

    /** 统计页签 */
    @Data
    @Builder
    public static class CallStatsVO {
        private CallVO call;
        /** 累计 Token */
        private Long totalTokens;
        /** 最近一次请求 Token（最后一轮 prompt） */
        private Long lastPromptTokens;
        /** 上下文窗口（激活模型配置），null 表示未知 */
        private Integer contextWindow;
        /** 上下文峰值占用百分比（本次请求所有 LLM 轮次中 prompt_tokens 最大值 / contextWindow） */
        private Double contextUsagePercent;
        /** 上下文占比估算：user / assistant / tool（百分比，按字符估算） */
        private Map<String, Double> contextBreakdown;
        /** 消息计数：user / assistant / tool */
        private Map<String, Long> messageCount;
        /** 输入/输出 Token 合计（全次调用） */
        private Long promptTokens;
        private Long completionTokens;
    }

    /** 轨迹页签：单个 Span 摘要（入参出参截断，全文走 spanDetail） */
    @Data
    @Builder
    public static class SpanVO {
        private Long id;
        private Integer round;
        private String spanType;
        private String toolName;
        private String toolCallId;
        private Long durationMs;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer success;
        private String errorMessage;
        private String inputExcerpt;
        private String outputExcerpt;
        private Boolean hasThink;
        private LocalDateTime createdAt;
        /** 所属 COMPACT span id（仅压缩摘要 LLM 使用） */
        private Long compactId;
    }

    /** 工具度量行 */
    @Data
    @Builder
    public static class ToolMetricVO {
        private String toolName;
        private Long count;
        private Long failures;
        private Long p50Ms;
        private Long p95Ms;
        private Long maxMs;
        private Long totalDurationMs;
    }

    /** 轨迹页签整体 */
    @Data
    @Builder
    public static class CallTraceVO {
        private Long sessionId;
        private String conversationId;
        /** 指标卡：LLM轮次 / 工具调用 / 工具失败 / Token合计 / 工具耗时合计 / 总时长 */
        private Integer llmRounds;
        private Long toolCalls;
        private Long toolFailures;
        private Long totalTokens;
        private Long toolDurationMs;
        private Long totalDurationMs;
        /** 每轮 Prompt 折线（round → promptTokens），上下文膨胀曲线 */
        private List<long[]> promptPerRound;
        /** 执行序列（按 id 排序的 Span 列表） */
        private List<SpanVO> spans;
        /** 工具度量（按 tool_name 聚合） */
        private List<ToolMetricVO> toolMetrics;
    }
}
