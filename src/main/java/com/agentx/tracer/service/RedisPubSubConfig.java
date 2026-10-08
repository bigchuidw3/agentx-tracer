package com.agentx.tracer.service;

import com.agentx.tracer.skill.MinioSkillContentStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis pub/sub 订阅容器：多实例模式下订阅中断广播和 skill 变更通知两个频道。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "deploy.mode", havingValue = "cluster")
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory, ConversationCoordinator coordinator,
            MinioSkillContentStore skillContentStore) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(coordinator, new ChannelTopic(ConversationCoordinator.channel()));
        // skill 变更通知：收到即触发本节点同步（从 MinIO 拉取），不必等定时周期
        container.addMessageListener((Message message, byte[] pattern) -> {
            log.info("[agentx-console] 收到 skill 变更通知，触发同步");
            skillContentStore.sync();
        }, new ChannelTopic(MinioSkillContentStore.channel()));
        return container;
    }
}
