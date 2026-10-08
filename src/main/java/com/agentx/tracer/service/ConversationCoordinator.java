package com.agentx.tracer.service;

import com.agentx.tracer.service.AgentService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * 多实例会话协调器（deploy.mode=cluster 时生效），两件事：
 * <ul>
 *   <li>会话执行锁：SETNX agentx:conv:lock:{convId} = 节点标识，同一会话全集群
 *       只允许一个实例执行，防止请求路由到不同实例导致并行执行；锁挂 TTL 兜底，
 *       执行节点崩溃没走到释放，TTL 到期自动失效</li>
 *   <li>中断广播：停止请求打到任意实例，本地打断失败说明执行在别的实例，
 *       发一条 pub/sub 广播（内容就是会话 ID），持有该会话的实例收到后执行本地打断</li>
 * </ul>
 * 单实例模式（standalone）下本 Bean 不创建，行为与改造前完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deploy.mode", havingValue = "cluster")
public class ConversationCoordinator implements MessageListener {

    private static final String LOCK_KEY = "agentx:conv:lock:";
    private static final String CHANNEL = "agentx:conv:interrupt";
    /** 锁 TTL：比最长一次执行更长，正常路径靠 doFinally 主动释放，TTL 只兜底节点崩溃 */
    private static final Duration LOCK_TTL = Duration.ofMinutes(30);

    /** 只删自己的锁：value 匹配才 del，防止误删其他节点刚抢到的新锁 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final AgentService agentService;

    @Value("${server.port:0}")
    private String port;

    @PostConstruct
    void init() {
        log.info("[agentx-console] 多实例协调器就绪: node=:{}, 中断广播频道={}", port, CHANNEL);
    }

    /**
     * 抢会话执行锁：抢到才允许发起执行，抢不到说明该会话在其他实例（或本实例）执行中。
     */
    public boolean tryLock(String conversationId) {
        Boolean ok = redis.opsForValue().setIfAbsent(LOCK_KEY + conversationId, port, LOCK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 释放会话执行锁（挂在执行流的 doFinally 上，执行终止才触发，HTTP 断开不影响）。
     */
    public void unlock(String conversationId) {
        redis.execute(UNLOCK_SCRIPT, List.of(LOCK_KEY + conversationId), port);
    }

    /**
     * 广播中断：各实例收到后各自尝试本地打断，持有该会话任务的那台真正执行，其余忽略。
     */
    public void broadcastInterrupt(String conversationId) {
        redis.convertAndSend(CHANNEL, conversationId);
        log.info("[agentx-console] 中断已广播: convId={}, from node=:{}", conversationId, port);
    }

    /**
     * 订阅回调：收到广播（内容为会话 ID），本地尝试打断。
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String conversationId = new String(message.getBody());
        boolean interrupted = agentService.interrupt(conversationId);
        log.info("[agentx-console] 收到中断广播: convId={}, 本地执行打断={}", conversationId, interrupted);
    }

    public static String channel() {
        return CHANNEL;
    }
}
