package com.agentx.tracer.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.agentx.tracer.chat.DynamicChatModelProvider;
import com.agentx.tracer.domain.dto.ObservDtos.CallStatsVO;
import com.agentx.tracer.domain.dto.ObservDtos.CallTraceVO;
import com.agentx.tracer.domain.dto.ObservDtos.CallVO;
import com.agentx.tracer.domain.dto.ObservDtos.SpanVO;
import com.agentx.tracer.domain.dto.ObservDtos.ToolMetricVO;
import com.agentx.tracer.domain.entities.AgentxConversation;
import com.agentx.tracer.mapper.AgentxConversationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测查询服务：调用列表（agentx_conversation 单表）+ 调用详情（统计/轨迹双页签）。
 *
 * <p>汇总表 + 明细表分层：列表/统计直接消费 conversation 观测列；
 * 轨迹按 session_id 精确取 agentx_trace 统一 Span（ORDER BY id 即执行顺序）。
 * 成本为实时折算口径（按当前激活模型单价）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ObservService {

    private static final int EXCERPT_LENGTH = 300;

    private final AgentxConversationMapper conversationMapper;
    private final DynamicChatModelProvider chatModelProvider;
    private final CostService costService;
    private final JdbcTemplate jdbcTemplate;

    // ======================================================================
    // 调用列表
    // ======================================================================

    /**
     * 分页查询调用列表：状态/模型/关键词（question 模糊）筛选，时间倒序。
     */
    public IPage<CallVO> listCalls(int page, int size, String status, String model, String keyword, String userId) {
        LambdaQueryWrapper<AgentxConversation> wrapper = new LambdaQueryWrapper<AgentxConversation>()
                .eq(AgentxConversation::getUserId, userId)
                .eq(StringUtils.hasText(status), AgentxConversation::getStatus, status)
                .eq(StringUtils.hasText(model), AgentxConversation::getModelName, model)
                .and(StringUtils.hasText(keyword), w -> w
                        .like(AgentxConversation::getQuestion, keyword)
                        .or().like(AgentxConversation::getSessionId, keyword)
                        .or().like(AgentxConversation::getConversationId, keyword))
                .orderByDesc(AgentxConversation::getId);

        IPage<AgentxConversation> result = conversationMapper.selectPage(new Page<>(page, size), wrapper);
        List<CallVO> vos = result.getRecords().stream().map(this::toCallVO).toList();

        Page<CallVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(vos);
        return voPage;
    }

    private CallVO toCallVO(AgentxConversation c) {
        long prompt = c.getPromptTokens() == null ? 0 : c.getPromptTokens();
        long completion = c.getCompletionTokens() == null ? 0 : c.getCompletionTokens();
        BigDecimal cost = costService.costOf(prompt, completion);
        return CallVO.builder()
                .id(c.getId())
                .sessionId(c.getSessionId())
                .conversationId(c.getConversationId())
                .userId(c.getUserId())
                .questionExcerpt(excerpt(c.getQuestion(), 100))
                .status(c.getStatus())
                .createdAt(c.getCreatedAt())
                .completedAt(c.getCompletedAt())
                .durationMs(c.getDurationMs())
                .rounds(c.getRounds())
                .toolCalls(c.getToolCalls())
                .toolFailures(c.getToolFailures())
                .promptTokens(c.getPromptTokens())
                .completionTokens(c.getCompletionTokens())
                .totalTokens(c.getTotalTokens())
                .cost(cost)
                .modelName(c.getModelName())
                .build();
    }

    // ======================================================================
    // 统计页签
    // ======================================================================

    /**
     * 调用统计：Token 累计/最近请求、上下文窗口占用、上下文占比（估算）、消息计数。
     */
    public CallStatsVO stats(long sessionId, String userId) {
        AgentxConversation c = conversationMapper.selectOne(
                new LambdaQueryWrapper<AgentxConversation>()
                        .eq(AgentxConversation::getSessionId, sessionId)
                        .eq(AgentxConversation::getUserId, userId));
        if (c == null) {
            throw new IllegalArgumentException("调用记录不存在: sessionId=" + sessionId);
        }

        // 最后一轮 LLM prompt（最近请求 Token）
        Long lastPrompt = queryLong(
                "SELECT prompt_tokens FROM agentx_trace WHERE session_id = ? AND span_type = 'LLM' ORDER BY id DESC LIMIT 1",
                sessionId);

        // 本次请求上下文峰值：所有 LLM span 里 prompt_tokens 的最大值（不受中途压缩影响）
        Long peakPrompt = 0L;
        try {
            Number v = jdbcTemplate.queryForObject(
                    "SELECT MAX(prompt_tokens) FROM agentx_trace WHERE session_id = ? AND span_type = 'LLM'",
                    Number.class, sessionId);
            peakPrompt = v == null ? 0L : v.longValue();
        } catch (Exception e) {
            // 无 LLM span 时峰值记为 0
        }

        // 上下文窗口峰值占用
        Integer contextWindow = null;
        Double usagePercent = null;
        var active = chatModelProvider.getActiveModel();
        if (active != null && active.getContextWindow() != null && active.getContextWindow() > 0) {
            contextWindow = active.getContextWindow();
            usagePercent = peakPrompt <= 0 ? 0.0
                    : Math.round(peakPrompt * 10000.0 / contextWindow) / 100.0;
        }

        // 上下文占比 + 消息计数（从会话消息链按角色估算，字符/4 近似）
        Map<String, Long> charsByRole = new LinkedHashMap<>();
        Map<String, Long> countByRole = new LinkedHashMap<>();
        analyzeMessages(c.getConversationId(), charsByRole, countByRole);

        // 从最近一次 LLM span 的入参（requestJson）提取系统提示词 + 工具定义
        long[] parts = promptPartsSize(sessionId);
        if (parts[0] > 0) {
            charsByRole.merge("system", parts[0], Long::sum);
            countByRole.merge("system", 1L, Long::sum);
        }
        if (parts[1] > 0) {
            charsByRole.merge("tool_schema", parts[1], Long::sum);
        }

        Map<String, Double> breakdown = new LinkedHashMap<>();
        long totalChars = charsByRole.values().stream().mapToLong(Long::longValue).sum();
        for (String role : List.of("system", "user", "assistant", "tool", "tool_schema")) {
            long ch = charsByRole.getOrDefault(role, 0L);
            breakdown.put(role, totalChars == 0 ? 0.0 : Math.round(ch * 10000.0 / totalChars) / 100.0);
        }

        return CallStatsVO.builder()
                .call(toCallVO(c))
                .totalTokens(c.getTotalTokens())
                .lastPromptTokens(lastPrompt)
                .contextWindow(contextWindow)
                .contextUsagePercent(usagePercent)
                .contextBreakdown(breakdown)
                .messageCount(countByRole)
                .promptTokens(c.getPromptTokens())
                .completionTokens(c.getCompletionTokens())
                .build();
    }

    // ======================================================================
    // 轨迹页签
    // ======================================================================

    /**
     * 轨迹：按 id 排序的统一 Span 执行序列 + 工具度量 + 每轮 Prompt 折线。
     */
    public CallTraceVO trace(long sessionId, String userId) {
        List<SpanVO> spans = jdbcTemplate.query(
                "SELECT id, round, span_type, tool_name, tool_call_id, duration_ms, prompt_tokens, completion_tokens, "
                        + "success, error_message, input_data, output_data, think, created_at, compact_id "
                        + "FROM agentx_trace WHERE session_id = ? AND user_id = ? ORDER BY id",
                (rs, i) -> {
                    String inputData = rs.getString("input_data");
                    String outputData = rs.getString("output_data");
                    String think = rs.getString("think");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    return SpanVO.builder()
                            .id(rs.getLong("id"))
                            .round(rs.getInt("round"))
                            .spanType(rs.getString("span_type"))
                            .toolName(rs.getString("tool_name"))
                            .toolCallId(rs.getString("tool_call_id"))
                            .durationMs(rs.getLong("duration_ms"))
                            .promptTokens(rs.getObject("prompt_tokens") == null ? 0 : rs.getInt("prompt_tokens"))
                            .completionTokens(rs.getObject("completion_tokens") == null ? 0 : rs.getInt("completion_tokens"))
                            .success(rs.getInt("success"))
                            .errorMessage(rs.getString("error_message"))
                            .inputExcerpt(excerpt(inputData, EXCERPT_LENGTH))
                            .outputExcerpt(excerpt(outputData, EXCERPT_LENGTH))
                            .hasThink(StringUtils.hasText(think))
                            .createdAt(createdAt == null ? null : createdAt.toLocalDateTime())
                            .compactId(rs.getObject("compact_id") == null ? null : rs.getLong("compact_id"))
                            .build();
                }, sessionId, userId);

        // 每轮 Prompt 折线（LLM Span）
        List<long[]> promptPerRound = new ArrayList<>();
        long totalTokens = 0;
        int llmRounds = 0;
        for (SpanVO s : spans) {
            if ("LLM".equals(s.getSpanType())) {
                promptPerRound.add(new long[]{s.getRound(),
                        s.getPromptTokens() == null ? 0 : s.getPromptTokens()});
                totalTokens += (s.getPromptTokens() == null ? 0 : s.getPromptTokens())
                        + (s.getCompletionTokens() == null ? 0 : s.getCompletionTokens());
                llmRounds++;
            }
        }

        // 工具度量（次数/失败/P50/P95/Max，Java 侧分位计算）
        List<ToolMetricVO> metrics = buildToolMetrics(spans);

        long toolDuration = spans.stream()
                .filter(s -> "TOOL".equals(s.getSpanType()))
                .mapToLong(s -> s.getDurationMs() == null ? 0 : s.getDurationMs()).sum();
        long toolCalls = spans.stream().filter(s -> "TOOL".equals(s.getSpanType())).count();
        long toolFailures = spans.stream()
                .filter(s -> "TOOL".equals(s.getSpanType()) && s.getSuccess() != null && s.getSuccess() == 0).count();

        // 总时长取 conversation 的 duration_ms（准确），兜底 trace 聚合
        Long totalDuration = queryLong(
                "SELECT duration_ms FROM agentx_conversation WHERE session_id = ? AND user_id = ?", sessionId, userId);

        return CallTraceVO.builder()
                .sessionId(sessionId)
                .llmRounds(llmRounds)
                .toolCalls(toolCalls)
                .toolFailures(toolFailures)
                .totalTokens(totalTokens)
                .toolDurationMs(toolDuration)
                .totalDurationMs(totalDuration == null ? 0 : totalDuration)
                .promptPerRound(promptPerRound)
                .spans(spans)
                .toolMetrics(metrics)
                .build();
    }

    /**
     * 单个 Span 全文（入参/出参/思考），详情展开用。
     */
    public Map<String, Object> spanDetail(long spanId, String userId) {
        return jdbcTemplate.queryForMap(
                "SELECT id, session_id, conversation_id, round, span_type, tool_name, tool_call_id, "
                        + "input_data, output_data, think, prompt_tokens, completion_tokens, "
                        + "duration_ms, success, error_message, created_at "
                        + "FROM agentx_trace WHERE id = ? AND user_id = ?", spanId, userId);
    }

    // ======================================================================
    // 私有方法
    // ======================================================================

    private List<ToolMetricVO> buildToolMetrics(List<SpanVO> spans) {
        Map<String, List<SpanVO>> byTool = new LinkedHashMap<>();
        for (SpanVO s : spans) {
            if ("TOOL".equals(s.getSpanType())) {
                byTool.computeIfAbsent(s.getToolName() == null ? "unknown" : s.getToolName(),
                        k -> new ArrayList<>()).add(s);
            }
        }
        List<ToolMetricVO> metrics = new ArrayList<>();
        for (Map.Entry<String, List<SpanVO>> e : byTool.entrySet()) {
            List<Long> durations = e.getValue().stream()
                    .map(s -> s.getDurationMs() == null ? 0L : s.getDurationMs()).sorted().toList();
            long failures = e.getValue().stream()
                    .filter(s -> s.getSuccess() != null && s.getSuccess() == 0).count();
            long totalDuration = e.getValue().stream()
                    .map(s -> s.getDurationMs() == null ? 0L : s.getDurationMs()).mapToLong(Long::longValue).sum();
            metrics.add(ToolMetricVO.builder()
                    .toolName(e.getKey())
                    .count((long) e.getValue().size())
                    .failures(failures)
                    .p50Ms(percentile(durations, 0.50))
                    .p95Ms(percentile(durations, 0.95))
                    .maxMs(durations.isEmpty() ? 0 : durations.getLast())
                    .totalDurationMs(totalDuration)
                    .build());
        }
        metrics.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
        return metrics;
    }

    private long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.clamp(idx, 0, sorted.size() - 1));
    }

    /**
     * 解析会话消息链（agentx_session.original_messages），按角色累计字符数与条数。
     * 消息 JSON 字段做兼容处理（messageType / role、textContent / text / content）。
     */
    private void analyzeMessages(String conversationId, Map<String, Long> charsByRole,
                                 Map<String, Long> countByRole) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            List<String> messages = jdbcTemplate.queryForList(
                    "SELECT state_data FROM agentx_session WHERE conversation_id = ? "
                            + "AND state_key = 'original_messages' ORDER BY item_index",
                    String.class, conversationId);
            for (String json : messages) {
                // state_data 是消息数组（MessageJsonSerializer.toJson 输出 List），需按数组解析
                JSONArray arr = JSONArray.parseArray(json);
                if (arr == null) {
                    continue;
                }
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject msg = arr.getJSONObject(i);
                    if (msg == null) {
                        continue;
                    }
                    String role = normalizeRole(msg.getString("messageType"), msg.getString("role"));
                    if (role == null) {
                        continue;
                    }
                    long length = textLength(msg);
                    charsByRole.merge(role, length, Long::sum);
                    countByRole.merge(role, 1L, Long::sum);

                    // 工具入参（assistant.tool_calls[].function.arguments）是 LLM 输出，计入 assistant，不是 tool
                    if ("assistant".equals(role)) {
                        JSONArray toolCalls = msg.getJSONArray("tool_calls");
                        if (toolCalls != null) {
                            for (int j = 0; j < toolCalls.size(); j++) {
                                JSONObject tc = toolCalls.getJSONObject(j);
                                JSONObject fn = tc == null ? null : tc.getJSONObject("function");
                                if (fn != null) {
                                    String args = fn.getString("arguments");
                                    if (args != null && !args.isBlank()) {
                                        charsByRole.merge("assistant", (long) args.length(), Long::sum);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[agentx-console] 消息链解析失败（占比/计数置空）: convId={}, err={}",
                    conversationId, e.getMessage());
        }
    }

    private String normalizeRole(String messageType, String role) {
        String raw = StringUtils.hasText(messageType) ? messageType : role;
        if (raw == null) {
            return null;
        }
        String r = raw.toLowerCase();
        if (r.contains("user")) return "user";
        if (r.contains("assistant")) return "assistant";
        if (r.contains("tool")) return "tool";
        if (r.contains("system")) return "system";
        return null;
    }

    private long textLength(JSONObject msg) {
        for (String key : List.of("textContent", "text", "content")) {
            String v = msg.getString(key);
            if (v != null && !v.isBlank()) {
                return v.length();
            }
        }
        return 0;
    }

    /**
     * 提取系统提示词 + 工具定义大小：取最近一次 LLM span 的入参（requestJson）。
     * requestJson 是 OpenAI Chat Completions 风格：messages 数组里 role=system 的是系统提示词，
     * tools 数组是工具定义（函数 schema）。返回 [系统提示词字符数, 工具定义字符数]。
     */
    private long[] promptPartsSize(long sessionId) {
        long[] result = {0L, 0L};
        try {
            String inputData = jdbcTemplate.queryForObject(
                    "SELECT input_data FROM agentx_trace WHERE session_id = ? AND span_type = 'LLM' "
                            + "ORDER BY id DESC LIMIT 1",
                    String.class, sessionId);
            if (inputData == null || inputData.isBlank()) {
                return result;
            }
            JSONObject req = JSONObject.parseObject(inputData);
            if (req == null) {
                return result;
            }
            // 工具定义（tools 数组）
            JSONArray tools = req.getJSONArray("tools");
            if (tools != null) {
                result[1] = tools.toJSONString().length();
            }
            // 系统提示词（messages 里 role=system 的 content）
            JSONArray msgs = req.getJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.size(); i++) {
                    JSONObject m = msgs.getJSONObject(i);
                    if (m == null) {
                        continue;
                    }
                    String role = m.getString("role");
                    if (role == null) {
                        role = m.getString("messageType");
                    }
                    if ("system".equalsIgnoreCase(role)) {
                        String content = m.getString("content");
                        if (content != null) {
                            result[0] += content.length();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败按 0 处理
        }
        return result;
    }

    private Long queryLong(String sql, Object... args) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
            return v == null ? 0L : v;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String excerpt(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
