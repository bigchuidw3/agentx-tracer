package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;
import com.agentx.tracer.service.OpikConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Opik 同步配置接口（按当前登录用户隔离）。
 */
@RestController
@RequestMapping("/opik")
@RequiredArgsConstructor
@SaCheckLogin
public class OpikConfigController {

    private final OpikConfigService opikConfigService;
    private final AuthService authService;

    @GetMapping("/config")
    public R<Map<String, Object>> getConfig() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        return R.ok(opikConfigService.get(userId));
    }

    @PostMapping("/config")
    public R<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        opikConfigService.save(userId, enabled);
        return R.ok();
    }

    /**
     * OTel 连通性测试：对全局内置端点发一次请求，判断网络是否可达（不落库）。
     */
    @PostMapping("/test")
    public R<Map<String, Object>> test() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        return R.ok(opikConfigService.testConnection());
    }
}
