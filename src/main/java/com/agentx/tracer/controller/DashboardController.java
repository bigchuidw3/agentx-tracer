package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;
import com.agentx.tracer.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 仪表盘接口：全局调用量 / Token / 成本 / 质量看板。
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@SaCheckLogin
public class DashboardController {

    private final DashboardService dashboardService;
    private final AuthService authService;

    /**
     * 仪表盘总览：KPI + 趋势 + 分布 + Top，按时间范围（当天 0 / 近 7 天 7 / 近 30 天 30）联动。
     */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "7") int days) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户身份获取失败，请重新登录");
        }
        return R.ok(dashboardService.overview(days, userId.toString()));
    }
}
