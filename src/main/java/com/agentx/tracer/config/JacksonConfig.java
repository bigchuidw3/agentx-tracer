package com.agentx.tracer.config;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 全局配置：Long 序列化为 String。
 * 雪花 ID（19 位）超过 JS Number 精度上限，前端会丢精度。
 * Module Bean 会被 Spring Boot 自动注册到 ObjectMapper。
 */
@Configuration
public class JacksonConfig {

    @Bean
    Module longToStringModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, ToStringSerializer.instance);
        return module;
    }
}
