package com.agentx.tracer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘统计服务：全局调用量 / Token / 成本 / 质量看板（1-3 主题的核心交付物）。
 *
 * <p>所有指标按时间范围（当天 / 近 7 天 / 近 30 天）联动，数据源为
 * agentx_conversation（调用边界汇总列）与 agentx_trace（工具级明细）。
 *
 * @author agentx-console
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final JdbcTemplate jdbcTemplate;
    private final CostService costService;

    /**
     * 一次返回仪表盘全部统计（KPI + 趋势 + 分布 + Top），前端一次加载。
     */
    public Map<String, Object> overview(int days, String userId) {
        LocalDateTime start = startTime(days);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kpi", kpi(start, userId));
        result.put("trend", trend(start, days <= 0, userId));
        result.put("toolDistribution", toolDistribution(start, userId));
        result.put("statusDistribution", statusDistribution(start, userId));
        result.put("topTokens", top("total_tokens", "tokens", start, userId));
        result.put("topDuration", top("duration_ms", "durationMs", start, userId));
        result.put("topRounds", top("rounds", "rounds", start, userId));
        result.put("topTools", top("tool_calls", "toolCalls", start, userId));
        return result;
    }

    private LocalDateTime startTime(int days) {
        return days <= 0
                ? LocalDate.now().atStartOfDay()
                : LocalDate.now().minusDays(days - 1).atStartOfDay();
    }

    // ==================== KPI ====================

    private Map<String, Object> kpi(LocalDateTime start, String userId) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT COUNT(*) AS calls, COALESCE(SUM(prompt_tokens),0) AS prompt, "
                            + "COALESCE(SUM(completion_tokens),0) AS completion, "
                            + "COALESCE(SUM(total_tokens),0) AS tokens, "
                            + "COALESCE(MAX(duration_ms),0) AS max_duration, "
                            + "COALESCE(AVG(rounds),0) AS avg_rounds, "
                            + "COALESCE(SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END),0) AS completed "
                            + "FROM agentx_conversation WHERE user_id = ? AND created_at >= ?",
                    userId, start);
            long calls = num(row.get("calls"));
            long prompt = num(row.get("prompt"));
            long completion = num(row.get("completion"));
            long completed = num(row.get("completed"));
            kpi.put("calls", calls);
            kpi.put("promptTokens", prompt);
            kpi.put("completionTokens", completion);
            kpi.put("totalTokens", num(row.get("tokens")));
            kpi.put("cost", costService.costOf(prompt, completion));
            kpi.put("successRate", calls == 0 ? 0 : Math.round(completed * 10000.0 / calls) / 100.0);
            kpi.put("maxDurationMs", num(row.get("max_duration")));
            kpi.put("avgRounds", num(row.get("avg_rounds")));
        } catch (Exception e) {
            log.warn("[agentx-console] 仪表盘 KPI 统计失败: {}", e.getMessage());
        }
        return kpi;
    }

    // ==================== 趋势 ====================

    private List<Map<String, Object>> trend(LocalDateTime start, boolean hourly, String userId) {
        String groupExpr = hourly ? "DATE_FORMAT(created_at, '%H:00')" : "DATE(created_at)";
        return queryList(
                "SELECT " + groupExpr + " AS d, COUNT(*) AS calls, "
                        + "COALESCE(SUM(total_tokens),0) AS tokens, "
                        + "COALESCE(SUM(CASE WHEN status='completed' THEN 1 ELSE 0 END),0) AS completed "
                        + "FROM agentx_conversation WHERE user_id = ? AND created_at >= ? "
                        + "GROUP BY " + groupExpr + " ORDER BY d",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    long calls = rs.getLong("calls");
                    row.put("label", rs.getString("d"));
                    row.put("calls", calls);
                    row.put("tokens", rs.getLong("tokens"));
                    row.put("successRate", calls == 0 ? 0
                            : Math.round(rs.getLong("completed") * 10000.0 / calls) / 100.0);
                    return row;
                },
                userId, start);
    }

    // ==================== 分布 ====================

    private List<Map<String, Object>> toolDistribution(LocalDateTime start, String userId) {
        return queryList(
                "SELECT tool_name, COUNT(*) AS cnt, "
                        + "COALESCE(SUM(CASE WHEN success=0 THEN 1 ELSE 0 END),0) AS failures, "
                        + "COALESCE(AVG(duration_ms),0) AS avg_ms "
                        + "FROM agentx_trace WHERE user_id = ? AND span_type='TOOL' AND created_at >= ? "
                        + "GROUP BY tool_name ORDER BY cnt DESC",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("toolName", rs.getString("tool_name"));
                    row.put("count", rs.getLong("cnt"));
                    row.put("failures", rs.getLong("failures"));
                    row.put("avgMs", rs.getLong("avg_ms"));
                    return row;
                },
                userId, start);
    }

    private List<Map<String, Object>> statusDistribution(LocalDateTime start, String userId) {
        return queryList(
                "SELECT status, COUNT(*) AS cnt FROM agentx_conversation WHERE user_id = ? AND created_at >= ? GROUP BY status",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", rs.getString("status"));
                    row.put("count", rs.getLong("cnt"));
                    return row;
                },
                userId, start);
    }

    // ==================== Top 榜 ====================

    private List<Map<String, Object>> top(String metricColumn, String valueKey, LocalDateTime start, String userId) {
        return queryList(
                "SELECT session_id, question, COALESCE(" + metricColumn + ",0) AS metric "
                        + "FROM agentx_conversation WHERE user_id = ? AND created_at >= ? "
                        + "ORDER BY " + metricColumn + " DESC LIMIT 5",
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String q = rs.getString("question");
                    row.put("sessionId", rs.getString("session_id"));
                    row.put("question", q != null && q.length() > 40 ? q.substring(0, 40) + "..." : q);
                    row.put(valueKey, rs.getLong("metric"));
                    return row;
                },
                userId, start);
    }

    // ==================== 辅助 ====================

    private List<Map<String, Object>> queryList(String sql,
                                                 org.springframework.jdbc.core.RowMapper<Map<String, Object>> mapper,
                                                 Object... args) {
        try {
            return jdbcTemplate.query(sql, mapper, args);
        } catch (Exception e) {
            log.warn("[agentx-console] 仪表盘统计失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0;
    }
}
