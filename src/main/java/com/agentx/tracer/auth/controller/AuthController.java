package com.agentx.tracer.auth.controller;

import com.agentx.tracer.auth.dto.LoginRequest;
import com.agentx.tracer.auth.dto.LoginUserVO;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（登录、登出、当前用户）。
 *
 * 不需要登录即可访问 /auth/login，其他接口需要登录（由 SaTokenConfig 拦截器控制）。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 登录。
     */
    @PostMapping("/login")
    public R<LoginUserVO> login(@RequestBody LoginRequest request) {
        return R.ok("登录成功", authService.login(request));
    }

    /**
     * 登出。
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authService.logout();
        return R.ok();
    }

    /**
     * 当前登录用户完整信息（含角色、部门、数据范围）。
     */
    @GetMapping("/me")
    public R<LoginUserVO> me() {
        return R.ok(authService.getCurrentUser());
    }

    /**
     * 修改当前登录用户密码。
     */
    @PostMapping("/change-password")
    public R<Void> changePassword(@RequestBody Map<String, String> body) {
        authService.changePassword(body.get("oldPassword"), body.get("newPassword"));
        return R.ok();
    }

    /**
     * 更新当前登录用户资料（昵称 / 真实姓名 / 邮箱）。
     */
    @PostMapping("/profile")
    public R<LoginUserVO> updateProfile(@RequestBody Map<String, String> body) {
        return R.ok(authService.updateProfile(
                body.get("nickname"),
                body.get("realName"),
                body.get("email")));
    }
}
