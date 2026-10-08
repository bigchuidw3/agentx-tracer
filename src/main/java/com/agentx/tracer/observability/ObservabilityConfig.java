package com.agentx.tracer.observability;

import com.agentx.ai.core.observability.ObservabilityPredicates;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性过滤（对齐 参考实现）：只保留 Agent/LLM 链路，
 * 拦截 HTTP 入口 / JDBC / 定时任务等框架噪声，观测平台里只有 Agent 调用链。
 *
 * @author agentx-console
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public ObservationPredicate onlyAgentObservations() {
        return ObservabilityPredicates.agentOnly();
    }
}
