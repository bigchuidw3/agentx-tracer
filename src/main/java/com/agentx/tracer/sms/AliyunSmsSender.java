package com.agentx.tracer.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.agentx.tracer.config.SmsProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信实现（app.sms.provider=aliyun 时装配）。
 * <p>凭据来自 app.sms.aliyun.*（AccessKey、签名、模板 Code），模板变量名为 code。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "aliyun")
public class AliyunSmsSender implements SmsSender {

    private final SmsProperties smsProperties;
    private volatile Client client;

    public AliyunSmsSender(SmsProperties smsProperties) {
        this.smsProperties = smsProperties;
    }

    @Override
    public void send(String phone, String code) {
        try {
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(smsProperties.getAliyun().getSignName())
                    .setTemplateCode(smsProperties.getAliyun().getTemplateCode())
                    .setTemplateParam("{\"code\":\"" + code + "\"}");
            SendSmsResponse response = client().sendSms(request);
            if (!"OK".equals(response.getBody().getCode())) {
                log.warn("阿里云短信发送失败: phone={}, code={}, message={}",
                        phone, response.getBody().getCode(), response.getBody().getMessage());
                throw new IllegalArgumentException("短信发送失败: " + response.getBody().getMessage());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("阿里云短信调用异常: phone={}", phone, e);
            throw new IllegalArgumentException("短信服务暂不可用，请稍后再试");
        }
    }

    private Client client() throws Exception {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    SmsProperties.Aliyun aliyun = smsProperties.getAliyun();
                    Config config = new Config()
                            .setAccessKeyId(aliyun.getAccessKeyId())
                            .setAccessKeySecret(aliyun.getAccessKeySecret())
                            .setEndpoint("dysmsapi.aliyuncs.com");
                    client = new Client(config);
                }
            }
        }
        return client;
    }
}
