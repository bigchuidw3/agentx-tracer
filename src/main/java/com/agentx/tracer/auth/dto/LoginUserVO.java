package com.agentx.tracer.auth.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 登录用户信息（登录成功 + /auth/me 接口返回）。
 *
 * <p>单管理员账号模型：仅基础信息 + Sa-Token 当前会话 token。
 */
@Data
public class LoginUserVO {

    private Long id;

    private String username;

    private String nickname;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 头像 URL */
    private String avatar;

    /**
     * 状态：ACTIVE-正常、DISABLED-禁用
     */
    private String status;

    /** 注册时间 */
    private LocalDateTime createdAt;

    /**
     * Sa-Token 当前会话 token，前端可用于 Header/URL 参数传递
     */
    private String token;
}
