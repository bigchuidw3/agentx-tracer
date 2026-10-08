package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;
import com.agentx.tracer.domain.dto.ObservDtos.CallStatsVO;
import com.agentx.tracer.domain.dto.ObservDtos.CallTraceVO;
import com.agentx.tracer.domain.dto.ObservDtos.CallVO;
import com.agentx.tracer.service.CostService;
import com.agentx.tracer.service.ObservService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 可观测接口：调用列表 + 调用详情（统计/轨迹双页签）+ Span 全文 + 预算概览。
 */
@Slf4j
@RestController
@RequestMapping("/observ")
@RequiredArgsConstructor
@SaCheckLogin
public class ObservController {

    private final ObservService observService;
    private final CostService costService;
    private final AuthService authService;

    /**
     * 调用列表（分页）：状态/模型/关键词筛选。
     */
    @GetMapping("/calls")
    public R<IPage<CallVO>> calls(@RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) String model,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(observService.listCalls(page, size, status, model, keyword, currentUserId()));
    }

    /**
     * 调用详情 · 统计页签。
     */
    @GetMapping("/calls/{sessionId}/stats")
    public R<CallStatsVO> stats(@PathVariable long sessionId) {
        return R.ok(observService.stats(sessionId, currentUserId()));
    }

    /**
     * 调用详情 · 轨迹页签（Span 执行序列 + 工具度量 + 每轮 Prompt 折线）。
     */
    @GetMapping("/calls/{sessionId}/trace")
    public R<CallTraceVO> trace(@PathVariable long sessionId) {
        return R.ok(observService.trace(sessionId, currentUserId()));
    }

    /**
     * 单个 Span 全文（入参/出参/思考），详情展开用。
     */
    @GetMapping("/span/{spanId}")
    public R<Map<String, Object>> spanDetail(@PathVariable long spanId) {
        return R.ok(observService.spanDetail(spanId, currentUserId()));
    }

    /**
     * 预算概览：当月成本 / 月度预算 / 预警阈值（大屏环形图数据）。
     */
    @GetMapping("/budget/overview")
    public R<Map<String, Object>> budgetOverview() {
        BigDecimal monthCost = costService.monthCost(currentUserId());
        Map<String, Double> cfg = costService.budgetConfig();
        double monthly = cfg.get("monthly");
        double cost = monthCost == null ? 0 : monthCost.doubleValue();
        double percent = monthly > 0 ? Math.round(cost * 10000 / monthly) / 100.0 : 0;
        return R.ok(Map.of(
                "monthCost", cost,
                "monthlyBudget", monthly,
                "warnRatio", cfg.get("warnRatio"),
                "percent", percent));
    }

    /** 当前登录用户 ID（字符串），未登录时直接 401。 */
    private String currentUserId() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份获取失败，请重新登录");
        }
        return userId.toString();
    }
}
