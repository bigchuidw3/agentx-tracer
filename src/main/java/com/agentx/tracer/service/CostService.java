package com.agentx.tracer.service;

import com.agentx.tracer.chat.DynamicChatModelProvider;
import com.agentx.tracer.domain.entities.AgentxAlert;
import com.agentx.tracer.mapper.AgentxAlertMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Token 成本计量与预算告警。
 *
 * <p>成本为"按当前激活模型单价实时折算"口径（不落库、不追溯历史单价变更）：
 * {@code cost = promptTokens/1e6 × inputPrice + completionTokens/1e6 × outputPrice}。
 *
 * <p>预算为云账单式月度阈值（sys_config: budget.monthly / budget.warn_ratio），
 * 每次调用结束后累计当月成本，越过阈值生成告警流水（同类型当天去重）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostService {

    private static final String TYPE_BUDGET_80 = "BUDGET_80";
    private static final String TYPE_BUDGET_100 = "BUDGET_100";

    private final DynamicChatModelProvider chatModelProvider;
    private final AgentxAlertMapper alertMapper;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 按激活模型单价折算成本（元）。无激活模型或未配单价时返回 null。
     */
    public BigDecimal costOf(long promptTokens, long completionTokens) {
        var model = chatModelProvider.getActiveModel();
        if (model == null || model.getInputPrice() == null || model.getOutputPrice() == null) {
            return null;
        }
        BigDecimal in = BigDecimal.valueOf(promptTokens)
                .multiply(model.getInputPrice()).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal out = BigDecimal.valueOf(completionTokens)
                .multiply(model.getOutputPrice()).divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return in.add(out).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 当月成本合计（按当前单价对当月全部调用折算）。
     */
    public BigDecimal monthCost(String userId) {
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        Long tokens = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(prompt_tokens),0) FROM agentx_conversation WHERE user_id = ? AND created_at >= ?",
                Long.class, userId, monthStart);
        Long completion = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(completion_tokens),0) FROM agentx_conversation WHERE user_id = ? AND created_at >= ?",
                Long.class, userId, monthStart);
        return costOf(tokens == null ? 0 : tokens, completion == null ? 0 : completion);
    }

    /**
     * 读取预算配置（sys_config KV）。
     */
    public Map<String, Double> budgetConfig() {
        Double monthly = readConfigAsDouble("budget.monthly", 100d);
        Double warnRatio = readConfigAsDouble("budget.warn_ratio", 0.8d);
        return Map.of("monthly", monthly, "warnRatio", warnRatio);
    }

    /**
     * 调用结束后预算判定：当月成本越阈值生成告警（同类型当天只记一条，避免流水刷屏）。
     */
    public void checkBudget(String conversationId, String userId) {
        try {
            BigDecimal monthCost = monthCost(userId);
            if (monthCost == null) {
                return;
            }
            Map<String, Double> cfg = budgetConfig();
            double monthly = cfg.get("monthly");
            if (monthly <= 0) {
                return;
            }
            double ratio = monthCost.doubleValue() / monthly;
            String type;
            if (ratio >= 1.0) {
                type = TYPE_BUDGET_100;
            } else if (ratio >= cfg.get("warnRatio")) {
                type = TYPE_BUDGET_80;
            } else {
                return;
            }
            // 当天同类型已告警则跳过
            Long exists = alertMapper.selectCount(new LambdaQueryWrapper<AgentxAlert>()
                    .eq(AgentxAlert::getAlertType, type)
                    .ge(AgentxAlert::getCreatedAt, LocalDate.now().atStartOfDay()));
            if (exists != null && exists > 0) {
                return;
            }
            AgentxAlert alert = new AgentxAlert();
            alert.setAlertType(type);
            alert.setConversationId(conversationId);
            alert.setDetail(String.format("当月Token成本 %.4f 元，已达月度预算 %.2f 元的 %.1f%%",
                    monthCost, monthly, ratio * 100));
            alert.setCreatedAt(LocalDateTime.now());
            alertMapper.insert(alert);
            log.warn("[agentx-console] 预算告警: type={}, 当月成本={}, 预算={}", type, monthCost, monthly);
        } catch (Exception e) {
            log.warn("[agentx-console] 预算判定失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private Double readConfigAsDouble(String key, double defaultValue) {
        try {
            String value = jdbcTemplate.queryForObject(
                    "SELECT cfg_value FROM sys_config WHERE cfg_key = ?", String.class, key);
            return value == null ? defaultValue : Double.parseDouble(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }
}
