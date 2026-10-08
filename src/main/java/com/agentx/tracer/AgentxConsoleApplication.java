package com.agentx.tracer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AgentX Console 启动类。
 * <p>
 * 基于 spring-ai-agentx 框架开发的豆豆智能助手
 */
@SpringBootApplication
@MapperScan({
        "com.agentx.tracer.mapper",
        "com.agentx.tracer.sys.mapper"
})
@EnableScheduling
public class AgentxConsoleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentxConsoleApplication.class, args);
    }
}
