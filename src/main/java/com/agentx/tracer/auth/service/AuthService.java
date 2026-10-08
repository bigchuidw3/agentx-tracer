package com.agentx.tracer.auth.service;

import com.agentx.tracer.auth.dto.LoginRequest;
import com.agentx.tracer.auth.dto.LoginUserVO;

/**
 * 认证服务接口。
 */
public interface AuthService {

    /**
     * 用户名密码登录。
     *
     * @return 登录用户完整信息（含 token）
     */
    LoginUserVO login(LoginRequest request);

    /**
     * 登出当前会话。
     */
    void logout();

    /**
     * 获取当前登录用户完整信息（角色 + 部门 + 数据范围）。
     * 依赖 Sa-Token ThreadLocal，仅能在 web 线程上调用。
     */
    LoginUserVO getCurrentUser();

    /**
     * 获取当前登录用户 ID（未登录返回 null）。
     * 依赖 Sa-Token ThreadLocal，仅能在 web 线程上调用。
     */
    Long getCurrentUserIdOrNull();

    /**
     * 按 userId 查询用户完整信息（角色 + 部门 + 数据范围）。
     * 纯数据库查询，不依赖 Sa-Token，可在异步线程上调用。
     */
    LoginUserVO getUserById(Long userId);

    /**
     * 修改当前登录用户密码（验证旧密码后更新新密码）。
     */
    void changePassword(String oldPassword, String newPassword);

    /**
     * 更新当前登录用户资料（昵称 / 真实姓名 / 邮箱）。
     * 手机号不在此处修改，走短信验证码换绑（{@code SmsAuthService.changePhone}）。
     *
     * @return 更新后的用户信息
     */
    LoginUserVO updateProfile(String nickname, String realName, String email);
}
