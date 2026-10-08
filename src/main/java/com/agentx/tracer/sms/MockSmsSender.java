package com.agentx.tracer.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mock 短信实现（app.sms.provider=mock 或未配置时装配）。
 * <p>验证码打印到后端日志（INFO 级别），用于开发联调与无短信凭据环境。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "mock", matchIfMissing = true)
public class MockSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code) {
        log.info("[SMS-MOCK] 手机号 {} 的验证码: {}（有效期见 app.sms.code-ttl-seconds）", phone, code);
    }
}
