package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;
import com.agentx.tracer.service.SandboxConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 沙箱执行开关接口（按当前登录用户隔离，默认开启）。
 */
@RestController
@RequestMapping("/sandbox")
@RequiredArgsConstructor
@SaCheckLogin
public class SandboxConfigController {

    private final SandboxConfigService sandboxConfigService;
    private final AuthService authService;

    @GetMapping("/config")
    public R<Map<String, Object>> getConfig() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        return R.ok(sandboxConfigService.get(userId));
    }

    @PostMapping("/config")
    public R<Void> saveConfig(@RequestBody Map<String, Object> body) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        sandboxConfigService.save(userId, enabled);
        return R.ok();
    }
}
