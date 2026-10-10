package com.agentx.tracer.auth.service.impl;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.agentx.tracer.auth.PasswordHasher;
import com.agentx.tracer.auth.dto.LoginRequest;
import com.agentx.tracer.auth.dto.LoginUserVO;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.sys.entity.SysUser;
import com.agentx.tracer.sys.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 认证服务实现（单管理员账号模型）。
 *
 * <p>登录流程：查用户 → 摘要校验密码 → 校验状态 → StpUtil.login → 返回 LoginUserVO。
 * 平台不开放注册，账号由 init.sql 种子初始化（默认 admin）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SysUserMapper sysUserMapper;

    @Override
    public LoginUserVO login(LoginRequest request) {
        if (request == null
                || isBlank(request.getUsername())
                || isBlank(request.getPassword())) {
            throw new IllegalArgumentException("用户名或密码不能为空");
        }

        // 1. 查用户
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, request.getUsername()));
        if (user == null) {
            throw new IllegalArgumentException("用户名不存在");
        }

        // 2. 校验密码（SHA-256 摘要，见 PasswordHasher）
        if (!PasswordHasher.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("密码错误");
        }

        // 3. 校验状态
        if (!STATUS_ACTIVE.equals(user.getStatus())) {
            throw new IllegalStateException("账号已被禁用，请联系管理员");
        }

        // 4. Sa-Token 登录
        StpUtil.login(user.getId());
        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());

        return buildLoginUserVO(user);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    @Override
    public LoginUserVO getCurrentUser() {
        Long userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new NotLoginException("未登录", null, null);
        }
        return getUserById(userId);
    }

    @Override
    public LoginUserVO getUserById(Long userId) {
        if (userId == null) {
            throw new NotLoginException("未登录", null, null);
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在: userId=" + userId);
        }
        return buildLoginUserVO(user);
    }

    @Override
    public Long getCurrentUserIdOrNull() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        try {
            return StpUtil.getLoginIdAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void changePassword(String oldPassword, String newPassword) {
        if (isBlank(oldPassword) || isBlank(newPassword)) {
            throw new IllegalArgumentException("旧密码或新密码不能为空");
        }
        Long userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new NotLoginException("未登录", null, null);
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在: userId=" + userId);
        }
        if (!PasswordHasher.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("旧密码错误");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(PasswordHasher.hash(newPassword));
        update.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(update);
        log.info("用户修改密码: userId={}, username={}", userId, user.getUsername());
    }

    @Override
    public LoginUserVO updateProfile(String nickname, String realName, String email) {
        Long userId = getCurrentUserIdOrNull();
        if (userId == null) {
            throw new NotLoginException("未登录", null, null);
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new IllegalStateException("用户不存在: userId=" + userId);
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setNickname(nickname);
        update.setRealName(realName);
        update.setEmail(email);
        update.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(update);
        log.info("用户更新资料: userId={}, username={}", userId, user.getUsername());
        return getUserById(userId);
    }

    /**
     * 构建 LoginUserVO：单账号平台，仅基础信息 + token。
     */
    private LoginUserVO buildLoginUserVO(SysUser user) {
        LoginUserVO vo = new LoginUserVO();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setRealName(user.getRealName());
        vo.setEmail(user.getEmail());
        vo.setPhone(user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        try {
            vo.setToken(StpUtil.getTokenValue());
        } catch (Exception e) {
            // getCurrentUser 可能在某些上下文下拿不到 token，忽略
        }
        return vo;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
