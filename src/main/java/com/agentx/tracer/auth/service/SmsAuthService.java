package com.agentx.tracer.auth.service;

import cn.dev33.satoken.stp.StpUtil;
import com.agentx.tracer.auth.dto.LoginUserVO;
import com.agentx.tracer.auth.service.impl.AuthServiceImpl;
import com.agentx.tracer.sms.SmsCodeService;
import com.agentx.tracer.sys.entity.SysUser;
import com.agentx.tracer.sys.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 手机号认证服务：验证码注册 / 短信登录 / 忘记密码重置（自 参考实现 移植适配 sa-token）。
 *
 * <p>注册即登录（注册成功直接 StpUtil.login）；手机号即账号（username = phone）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsAuthService {

    private final SysUserMapper sysUserMapper;
    private final SmsCodeService smsCodeService;
    private final AuthServiceImpl authService;

    /**
     * 发送验证码。
     */
    public void sendCode(String phone) {
        smsCodeService.sendCode(phone);
    }

    /**
     * 手机号是否已注册（发码前预校验）。
     */
    public boolean isPhoneRegistered(String phone) {
        return findByPhone(phone) != null;
    }

    /**
     * 自注册：验证码 → 手机号唯一 + 用户名唯一 → 创建用户 → 注册即登录。
     */
    public LoginUserVO register(String phone, String code, String password, String username, String realName) {
        smsCodeService.verify(phone, code);
        if (findByPhone(phone) != null) {
            throw new IllegalArgumentException("该手机号已注册，请直接登录");
        }
        if (findByUsername(username) != null) {
            throw new IllegalArgumentException("该用户名已被使用，请更换");
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(password);
        user.setPhone(phone);
        user.setRealName(realName);
        user.setNickname(realName != null && !realName.isBlank() ? realName : username);
        user.setStatus("ACTIVE");
        sysUserMapper.insert(user);

        log.info("自注册成功: username={}, phone={}", username, maskPhone(phone));
        StpUtil.login(user.getId());
        return authService.getUserById(user.getId());
    }

    /**
     * 用户名是否已被占用（注册页发码前预校验）。
     */
    public boolean isUsernameTaken(String username) {
        return findByUsername(username) != null;
    }

    /**
     * 手机号验证码登录：用户不存在提示先注册。
     */
    public LoginUserVO smsLogin(String phone, String code) {
        smsCodeService.verify(phone, code);
        SysUser user = findByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("该手机号未注册，请先注册");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new IllegalArgumentException("账号已被禁用，请联系管理员");
        }
        log.info("手机号验证码登录成功: {}", maskPhone(phone));
        StpUtil.login(user.getId());
        return authService.getUserById(user.getId());
    }

    /**
     * 忘记密码 — 短信验证码重置：验证码 → 用户存在 → 更新密码。
     */
    public void resetPassword(String phone, String code, String newPassword) {
        smsCodeService.verify(phone, code);
        SysUser user = findByPhone(phone);
        if (user == null) {
            throw new IllegalArgumentException("该手机号未注册");
        }
        SysUser update = new SysUser();
        update.setId(user.getId());
        update.setPassword(newPassword);
        sysUserMapper.updateById(update);
        log.info("短信验证码重置密码成功: {}", maskPhone(phone));
    }

    /**
     * 修改当前登录用户的手机号：验证新手机号验证码 → 新手机号未被占用 → 更新。
     */
    public LoginUserVO changePhone(String newPhone, String code) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new cn.dev33.satoken.exception.NotLoginException("未登录", null, null);
        }
        smsCodeService.verify(newPhone, code);
        SysUser existing = findByPhone(newPhone);
        if (existing != null && !existing.getId().equals(userId)) {
            throw new IllegalArgumentException("该手机号已被其他账号使用");
        }
        SysUser update = new SysUser();
        update.setId(userId);
        update.setPhone(newPhone);
        update.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(update);
        log.info("修改手机号成功: {}", maskPhone(newPhone));
        return authService.getUserById(userId);
    }

    private SysUser findByPhone(String phone) {
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
    }

    private SysUser findByUsername(String username) {
        return sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
    }

    private static String maskPhone(String phone) {
        return phone == null || phone.length() < 7 ? phone
                : phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
