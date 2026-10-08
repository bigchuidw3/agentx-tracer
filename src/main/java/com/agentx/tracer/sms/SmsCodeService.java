package com.agentx.tracer.sms;

import com.agentx.tracer.config.SmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 短信验证码服务 — Redis 存储（对齐 参考实现 生命周期约束）。
 * <ul>
 *   <li>验证码 TTL = app.sms.code-ttl-seconds（默认 5 分钟）</li>
 *   <li>同一手机号发送冷却 = app.sms.send-cooldown-seconds（默认 60s）</li>
 *   <li>验证错误 app.sms.max-verify-attempts 次后验证码作废</li>
 *   <li>验证成功即消费（删除），不可重复使用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmsCodeService {

    private static final String CODE_KEY = "agentx:sms:code:";
    private static final String COOLDOWN_KEY = "agentx:sms:cooldown:";
    private static final String ATTEMPTS_KEY = "agentx:sms:attempts:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;
    private final SmsProperties smsProperties;
    private final SmsSender smsSender;

    /**
     * 生成并发送验证码（冷却期内拒绝重复发送）。
     */
    public void sendCode(String phone) {
        if (Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_KEY + phone))) {
            throw new IllegalArgumentException("发送过于频繁，请稍后再试");
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        smsSender.send(phone, code);
        redisTemplate.opsForValue().set(CODE_KEY + phone, code,
                Duration.ofSeconds(smsProperties.getCodeTtlSeconds()));
        redisTemplate.opsForValue().set(COOLDOWN_KEY + phone, "1",
                Duration.ofSeconds(smsProperties.getSendCooldownSeconds()));
        redisTemplate.delete(ATTEMPTS_KEY + phone);
    }

    /**
     * 校验验证码：错误计数超限作废，成功即消费。
     */
    public void verify(String phone, String code) {
        String stored = redisTemplate.opsForValue().get(CODE_KEY + phone);
        if (stored == null) {
            throw new IllegalArgumentException("验证码已过期，请重新获取");
        }
        if (!stored.equals(code)) {
            String key = ATTEMPTS_KEY + phone;
            Long current = redisTemplate.opsForValue().increment(key);
            redisTemplate.expire(key, Duration.ofSeconds(smsProperties.getCodeTtlSeconds()));
            int attempts = current == null ? 1 : current.intValue();
            if (attempts >= smsProperties.getMaxVerifyAttempts()) {
                redisTemplate.delete(CODE_KEY + phone);
                redisTemplate.delete(ATTEMPTS_KEY + phone);
                throw new IllegalArgumentException("验证码错误次数过多已作废，请重新获取");
            }
            throw new IllegalArgumentException("验证码错误");
        }
        redisTemplate.delete(CODE_KEY + phone);
        redisTemplate.delete(ATTEMPTS_KEY + phone);
    }
}
