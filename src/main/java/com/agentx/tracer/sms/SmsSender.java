package com.agentx.tracer.sms;

/**
 * 短信发送接口 — 由 app.sms.provider 决定装配实现（mock / aliyun / pnvs）。
 */
public interface SmsSender {

    /**
     * 发送验证码短信。
     *
     * @param phone 目标手机号（11 位）
     * @param code  6 位验证码
     */
    void send(String phone, String code);
}
