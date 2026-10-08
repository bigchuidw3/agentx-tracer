package com.agentx.tracer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信验证码配置（对齐 参考实现）。
 * <p>
 * provider = mock（默认）：验证码打印到后端日志，用于开发联调；<br>
 * provider = aliyun：阿里云短信（正式通道，需企业资质+签名+模板）；<br>
 * provider = pnvs：阿里云号码认证「短信认证」（免资质，用系统赠送签名+模板）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.sms")
public class SmsProperties {

    /** 短信实现：mock | aliyun | pnvs */
    private String provider = "mock";

    /** 验证码有效期（秒） */
    private int codeTtlSeconds = 300;

    /** 同一手机号发送冷却（秒） */
    private int sendCooldownSeconds = 60;

    /** 验证码最大验证错误次数，超过作废 */
    private int maxVerifyAttempts = 5;

    private Aliyun aliyun = new Aliyun();

    private Pnvs pnvs = new Pnvs();

    @Data
    public static class Aliyun {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String signName = "";
        private String templateCode = "";
    }

    /** PNVS：系统赠送签名/模板，不可自定义 */
    @Data
    public static class Pnvs {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String signName = "";
        private String templateCode = "";
    }
}
