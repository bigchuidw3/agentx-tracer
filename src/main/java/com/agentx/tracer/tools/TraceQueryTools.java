package com.agentx.tracer.tools;

import com.agentx.tracer.service.TraceQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 诊断 Agent 的 trace 查询工具集：通读全程 / 查一轮 / 查首现。
 * <p>
 * 三个工具模拟人工排查路径：先 {@link #traceOverview} 通读全程锁定可疑轮次，
 * 再 {@link #traceRound} 还原可疑轮现场（失败轮只有这里能看到），
 * 配合 {@link #traceFirstAppear} 以错误值反查源头。查询逻辑都在 {@link TraceQueryService}。
 */
@Component
@RequiredArgsConstructor
public class TraceQueryTools {

    private final TraceQueryService traceQueryService;

    /**
     * 工具一：通读全程，委托 {@link TraceQueryService#overview}。
     */
    @Tool(name = "traceOverview",
            description = "通读被测 Agent 的完整执行过程（先调这个）。返回：1) 窗口内每次对话的"
                    + " session 编号、用户、问题和状态；2) 轮次概览（只列失败轮及报错、上下文压缩点，"
                    + "未列出的轮次均成功）；3) 消息记录预览（每行标 item 序号，assistant 行标注 round，"
                    + "正文截断、省略模型思考，按执行顺序）。")
    public String traceOverview(
            @ToolParam(description = "被测会话窗口的 conversation_id")
            String conversationId,
            ToolContext toolContext) {
        return traceQueryService.overview(conversationId, userId(toolContext));
    }

    /**
     * 工具二：查一轮，委托 {@link TraceQueryService#roundDetail}。
     */
    @Tool(name = "traceRound",
            description = "查某一轮 LLM 调用的完整入参出参。input_data 是当时模型看到的完整请求"
                    + "（历史消息与顶层参数；tools 工具定义各轮相同且篇幅大，默认省略，"
                    + "需核对工具 schema 时传 includeTools=true），output_data 是其输出"
                    + "（工具轮是 tool_calls，最终轮是回答文本）。失败轮的详情只有这里能看到。")
    public String traceRound(
            @ToolParam(description = "session 编号（traceOverview 返回的会话概览里的数字编号）")
            String sessionId,
            @ToolParam(description = "轮次编号，session 内从 1 开始")
            Integer round,
            @ToolParam(description = "是否附带 tools 工具定义，默认 false 省略", required = false)
            Boolean includeTools,
            ToolContext toolContext) {
        return traceQueryService.roundDetail(sessionId, round, includeTools, userId(toolContext));
    }

    /**
     * 工具三：查首现，委托 {@link TraceQueryService#firstAppear}。
     */
    @Tool(name = "traceFirstAppear",
            description = "反查一个关键词第一次出现在哪条消息（命中即停），返回首现位置"
                    + "（item_index、session、消息角色）与前后各约 120 字符的原文片段。"
                    + "搜索覆盖正文、模型思考、工具调用参数、工具结果。")
    public String traceFirstAppear(
            @ToolParam(description = "被测会话窗口的 conversation_id")
            String conversationId,
            @ToolParam(description = "要检索的关键词，如错误的数值、字段名、结论短语")
            String keyword,
            ToolContext toolContext) {
        return traceQueryService.firstAppear(conversationId, keyword, userId(toolContext));
    }

    /** 从工具执行上下文读取当前 userId（框架 buildToolContext 注入），隔离诊断查询。 */
    private static String userId(ToolContext toolContext) {
        if (toolContext == null || toolContext.getContext() == null) {
            return null;
        }
        Object v = toolContext.getContext().get("userId");
        return v == null ? null : v.toString();
    }
}
