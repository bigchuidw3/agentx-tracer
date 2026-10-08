package com.agentx.tracer.auth.controller;

import com.agentx.tracer.auth.dto.LoginUserVO;
import com.agentx.tracer.auth.dto.SmsAuthDtos.ChangePhoneRequest;
import com.agentx.tracer.auth.dto.SmsAuthDtos.RegisterRequest;
import com.agentx.tracer.auth.dto.SmsAuthDtos.ResetPasswordRequest;
import com.agentx.tracer.auth.dto.SmsAuthDtos.SendCodeRequest;
import com.agentx.tracer.auth.dto.SmsAuthDtos.SmsLoginRequest;
import com.agentx.tracer.auth.service.SmsAuthService;
import com.agentx.tracer.common.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 手机号认证接口：验证码发送 / 注册（注册即登录） / 短信登录 / 忘记密码重置。
 * 路径在 /auth/** 下，由 SaTokenConfig 统一放行。
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class SmsAuthController {

    private final SmsAuthService smsAuthService;

    /**
     * 发送验证码（60 秒冷却，5 分钟有效）。
     */
    @PostMapping("/sms/send-code")
    public R<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        smsAuthService.sendCode(request.phone());
        return R.ok("验证码已发送", null);
    }

    /**
     * 手机号是否已注册（发码前预校验；true=已注册）。
     */
    @GetMapping("/sms/check-phone")
    public R<Boolean> checkPhone(@RequestParam String phone) {
        return R.ok(smsAuthService.isPhoneRegistered(phone));
    }

    /**
     * 自注册：手机号 + 验证码 + 密码 + 用户名，注册成功直接登录。
     */
    @PostMapping("/register")
    public R<LoginUserVO> register(@Valid @RequestBody RegisterRequest request) {
        return R.ok("注册成功", smsAuthService.register(
                request.phone(), request.code(), request.password(), request.username(), request.realName()));
    }

    /**
     * 用户名是否已被占用（注册页发码前预校验；true=已占用）。
     */
    @GetMapping("/sms/check-username")
    public R<Boolean> checkUsername(@RequestParam String username) {
        return R.ok(smsAuthService.isUsernameTaken(username));
    }

    /**
     * 手机号验证码登录。
     */
    @PostMapping("/sms/login")
    public R<LoginUserVO> smsLogin(@Valid @RequestBody SmsLoginRequest request) {
        return R.ok("登录成功", smsAuthService.smsLogin(request.phone(), request.code()));
    }

    /**
     * 忘记密码 — 短信验证码重置。
     */
    @PostMapping("/reset-password")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        smsAuthService.resetPassword(request.phone(), request.code(), request.newPassword());
        return R.ok("密码已重置，请使用新密码登录", null);
    }

    /**
     * 修改当前登录用户手机号（需新手机号验证码，登录后调用）。
     */
    @PostMapping("/change-phone")
    public R<LoginUserVO> changePhone(@Valid @RequestBody ChangePhoneRequest request) {
        return R.ok("手机号修改成功", smsAuthService.changePhone(request.newPhone(), request.code()));
    }
}
