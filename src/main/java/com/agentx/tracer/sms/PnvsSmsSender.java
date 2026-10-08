package com.agentx.tracer.sms;

import com.aliyun.dypnsapi20170525.Client;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeRequest;
import com.aliyun.dypnsapi20170525.models.SendSmsVerifyCodeResponse;
import com.aliyun.teaopenapi.models.Config;
import com.agentx.tracer.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云号码认证「短信认证」实现（app.sms.provider=pnvs 时装配）。
 * <p>免资质通道：使用号码认证控制台系统赠送的签名与模板（必须配套，不可自定义）。
 * 验证码由本地生成，经 TemplateParam 直接下发具体值，生成/存储/比对仍由本地 Redis 闭环完成。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "pnvs")
public class PnvsSmsSender implements SmsSender {

    private final SmsProperties smsProperties;
    private volatile Client client;

    public PnvsSmsSender(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
    }

    @Override
    public void send(String phone, String code) {
        try {
            int validMinutes = (smsProperties.getCodeTtlSeconds() + 59) / 60;
            SendSmsVerifyCodeRequest request = new SendSmsVerifyCodeRequest()
                    .setPhoneNumber(phone)
                    .setSignName(smsProperties.getPnvs().getSignName())
                    .setTemplateCode(smsProperties.getPnvs().getTemplateCode())
                    .setTemplateParam("{\"code\":\"" + code + "\",\"min\":\"" + validMinutes + "\"}")
                    .setInterval((long) smsProperties.getSendCooldownSeconds())
                    .setValidTime((long) smsProperties.getCodeTtlSeconds())
                    .setReturnVerifyCode(false);
            SendSmsVerifyCodeResponse response = client().sendSmsVerifyCode(request);
            if (!"OK".equals(response.getBody().getCode()) || !Boolean.TRUE.equals(response.getBody().getSuccess())) {
                log.warn("PNVS 短信发送失败: phone={}, code={}, message={}",
                        phone, response.getBody().getCode(), response.getBody().getMessage());
                throw new IllegalArgumentException("短信发送失败: " + response.getBody().getMessage());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("PNVS 短信调用异常: phone={}", phone, e);
            throw new IllegalArgumentException("短信服务暂不可用，请稍后再试");
        }
    }

    private Client client() throws Exception {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    SmsProperties.Pnvs pnvs = smsProperties.getPnvs();
                    Config config = new Config()
                            .setAccessKeyId(pnvs.getAccessKeyId())
                            .setAccessKeySecret(pnvs.getAccessKeySecret())
                            .setEndpoint("dypnsapi.aliyuncs.com");
                    client = new Client(config);
                }
            }
        }
        return client;
    }
}
