package com.agentx.tracer.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 手机号认证 DTO（record）。
 */
public final class SmsAuthDtos {

    private SmsAuthDtos() {
    }

    public record SendCodeRequest(
            @NotBlank(message = "手机号不能为空") String phone) {
    }

    public record RegisterRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code,
            @NotBlank(message = "密码不能为空") String password,
            @NotBlank(message = "用户名不能为空") String username,
            String realName) {
    }

    public record SmsLoginRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "手机号不能为空") String phone,
            @NotBlank(message = "验证码不能为空") String code,
            @NotBlank(message = "新密码不能为空") String newPassword) {
    }

    public record ChangePhoneRequest(
            @NotBlank(message = "新手机号不能为空") String newPhone,
            @NotBlank(message = "验证码不能为空") String code) {
    }
}
