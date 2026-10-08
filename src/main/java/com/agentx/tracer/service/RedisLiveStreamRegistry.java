package com.agentx.tracer.service;

import com.agentx.ai.core.interrupt.PauseReason;
import com.agentx.ai.core.interrupt.SafePoint;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.PauseState;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.ConnectableFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进行中的流式回答注册表（Redis 版，deploy.mode=cluster 时生效）。
 * <p>
 * 打字机路径在本节点内存（与单机版一致，输出速度无差别），配合 nginx 粘性路由重连回到本节点。
 * 事件同时异步写入 Redis Stream（跨实例共享缓冲，与 Vercel AI SDK 的 resumable streams 同款模式）：
 * 重连万一落到其他节点（failover/扩缩容），从 Redis 按 startSeq 补发再接实时尾流。
 * <p>
 * 旁路写入遵循"零接触主路径"：事件分发线程上唯一的旁路动作是入队一个对象引用（纳秒级），
 * 序列化、攒批、pipeline 网络往返全部在旁路线程完成；队列有界，满则丢弃（极端情况牺牲
 * 续传完整性），Redis 无论多慢都拖不到流式输出。
 * <p>
 * key 设计：
 * <ul>
 *   <li>agentx:stream:{convId}：事件流（entry 带 seq 和 evt JSON），流结束保留 10 分钟供迟到补发</li>
 *   <li>agentx:stream:active：一个 Hash 装全部进行中会话（field=convId，value=ownerUserId），
 *       跨节点的"生成中"标记与并发判断；整体 TTL 兜底节点崩溃</li>
 * </ul>
 * <p>
 * Complete / Paused / Error 事件本身就是终止信号，读到即结束，不需要额外哨兵。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "deploy.mode", havingValue = "cluster")
public class RedisLiveStreamRegistry implements LiveStreamRegistry {

    private static final String STREAM_KEY = "agentx:stream:";
    private static final String ACTIVE_KEY = "agentx:stream:active";
    private static final Duration ACTIVE_TTL = Duration.ofMinutes(10);
    private static final Duration STREAM_TTL = Duration.ofMinutes(10);
    private static final int SIDECAR_CAPACITY = 2000;
    private static final int WRITE_BATCH = 500;

    private final StringRedisTemplate redis;

    private final Map<String, LocalStream> live = new ConcurrentHashMap<>();

    /**
     * 一条进行中的流：可重放的 SSE 流（事件已带序号）+ 属主用户。
     */
    private record LocalStream(ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay,
                               Long ownerUserId) {
    }

    private final ObjectMapper mapper = buildMapper();

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> register(String conversationId, Long ownerUserId,
                                                             Flux<AgentStreamEvent> source) {
        String streamKey = STREAM_KEY + conversationId;
        BlockingQueue<ServerSentEvent<AgentStreamEvent>> sidecar = new ArrayBlockingQueue<>(SIDECAR_CAPACITY);
        AtomicBoolean writerDone = new AtomicBoolean(false);

        // 本机可重放流：connect 立即驱动执行，打字机路径全程内存
        AtomicReference<LocalStream> self = new AtomicReference<>();
        ConnectableFlux<ServerSentEvent<AgentStreamEvent>> replay = source
                .index((i, evt) -> ServerSentEvent.builder(evt).id(String.valueOf(i + 1)).build())
                .doFinally(signal -> {
                    writerDone.set(true);
                    redis.opsForHash().delete(ACTIVE_KEY, conversationId);
                    redis.expire(streamKey, STREAM_TTL);
                    LocalStream entry = self.get();
                    if (entry != null) {
                        live.remove(conversationId, entry);
                    }
                    log.info("[agentx-console] 流式回答结束: convId={}, signal={}", conversationId, signal);
                })
                .replay();
        LocalStream entry = new LocalStream(replay, ownerUserId);
        self.set(entry);
        live.put(conversationId, entry);

        // 新一轮执行：清残留、标记进行中（跨节点可见）
        redis.delete(streamKey);
        redis.opsForHash().put(ACTIVE_KEY, conversationId, String.valueOf(ownerUserId));
        redis.expire(ACTIVE_KEY, ACTIVE_TTL);
        // 事件流先挂长 TTL 兜底节点崩溃（doFinally 没执行也不残留），正常结束时缩短为 10 分钟；
        // XADD 不会重置 TTL，执行中不会被中途清掉
        redis.expire(streamKey, Duration.ofMinutes(30));
        replay.connect();

        // 旁路订阅：主路径上的唯一动作，入队对象引用（纳秒级），无序列化、无网络
        replay.subscribe(
                sse -> {
                    if (!writerDone.get()) {
                        sidecar.offer(sse);
                    }
                },
                err -> log.warn("[agentx-console] 旁路订阅异常: convId={}, err={}", conversationId, err.getMessage()));

        // 旁路线程：序列化 + 攒批 + pipeline 一次往返写整批，不影响主路径
        Thread.ofVirtual().name("stream-writer-" + conversationId).start(() -> {
            List<ServerSentEvent<AgentStreamEvent>> buf = new ArrayList<>();
            while (!(writerDone.get() && sidecar.isEmpty())) {
                try {
                    ServerSentEvent<AgentStreamEvent> first = sidecar.poll(500, TimeUnit.MILLISECONDS);
                    if (first == null) {
                        continue;
                    }
                    buf.clear();
                    buf.add(first);
                    sidecar.drainTo(buf, WRITE_BATCH);
                    writeBatch(streamKey, conversationId, buf);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });

        log.info("[agentx-console] 流注册(redis): convId={}, owner={}", conversationId, ownerUserId);
        return replay;
    }

    /**
     * 一批事件一次 pipeline 写入（一次网络往返摊到整批），失败跳过本批，续传容忍少量缺事件。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void writeBatch(String streamKey, String conversationId,
                            List<ServerSentEvent<AgentStreamEvent>> buf) {
        try {
            List<MapRecord<String, String, String>> records = new ArrayList<>(buf.size());
            for (ServerSentEvent<AgentStreamEvent> sse : buf) {
                String json = toJson(slim(sse.data()));
                if (json != null) {
                    records.add(MapRecord.create(streamKey, Map.of("seq", sse.id(), "evt", json)));
                }
            }
            redis.executePipelined(new SessionCallback() {
                @Override
                public Object execute(RedisOperations ops) {
                    for (MapRecord<String, String, String> r : records) {
                        ops.opsForStream().add(r);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            log.warn("[agentx-console] 事件批量写 Redis 失败（跳过本批）: convId={}, size={}, err={}",
                    conversationId, buf.size(), e.getMessage());
        }
    }

    @Override
    public Flux<ServerSentEvent<AgentStreamEvent>> attach(String conversationId, long startSeq) {
        if (!isActive(conversationId)) {
            return null;
        }
        long from = Math.max(startSeq, 1);
        // 本节点发起的流：内存直通（与单机版一致）
        LocalStream entry = live.get(conversationId);
        if (entry != null) {
            return entry.replay().skipUntil(sse -> Long.parseLong(sse.id()) >= from);
        }
        // 流在其他节点（failover/重启）：从 Redis 补发缓存再接实时尾流，虚拟线程跑阻塞读
        String streamKey = STREAM_KEY + conversationId;
        return Flux.create(emitter -> {
            Thread reader = Thread.ofVirtual().name("stream-attach-" + conversationId).unstarted(() ->
                    readLoop(streamKey, conversationId, from, emitter));
            emitter.onDispose(() -> reader.interrupt());
            reader.start();
        });
    }

    /**
     * 跨节点读循环：先 XRANGE 全量补发（按 seq 过滤），再 XREAD BLOCK 接实时尾流，
     * 读到终止事件（Complete/Paused/Error）即结束。执行节点崩溃没写终止事件时，
     * 靠 active 标记的 TTL 过期兜底：空读周期发现标记没了就结束并清理事件流。
     */
    private void readLoop(String streamKey, String conversationId, long from,
                          FluxSink<ServerSentEvent<AgentStreamEvent>> emitter) {
        String lastId = "0-0";
        try {
            List<MapRecord<String, Object, Object>> history =
                    redis.opsForStream().range(streamKey, Range.unbounded());
            if (history != null) {
                for (MapRecord<String, Object, Object> record : history) {
                    lastId = record.getId().getValue();
                    if (emit(record, from, emitter)) {
                        return;
                    }
                }
            }
            while (!emitter.isCancelled()) {
                List<MapRecord<String, Object, Object>> batch = redis.opsForStream().read(
                        StreamReadOptions.empty().block(Duration.ofSeconds(30)),
                        StreamOffset.create(streamKey, ReadOffset.from(lastId)));
                if (batch == null || batch.isEmpty()) {
                    if (!isActive(conversationId)) {
                        redis.expire(streamKey, Duration.ofMinutes(1));
                        return;
                    }
                    continue;
                }
                for (MapRecord<String, Object, Object> record : batch) {
                    lastId = record.getId().getValue();
                    if (emit(record, from, emitter)) {
                        return;
                    }
                }
            }
        } catch (Exception e) {
            if (!emitter.isCancelled()) {
                log.warn("[agentx-console] 续读取流中断: convId={}, err={}", conversationId, e.getMessage());
            }
        } finally {
            emitter.complete();
        }
    }

    /**
     * 发送一条事件，返回 true 表示是终止事件（流结束）。
     */
    private boolean emit(MapRecord<String, Object, Object> record, long from,
                         FluxSink<ServerSentEvent<AgentStreamEvent>> emitter) {
        Map<Object, Object> fields = record.getValue();
        String seq = String.valueOf(fields.get("seq"));
        String json = String.valueOf(fields.get("evt"));
        if (Long.parseLong(seq) < from) {
            return false;
        }
        AgentStreamEvent event = fromJson(json);
        if (event == null) {
            return false;
        }
        emitter.next(ServerSentEvent.builder(event).id(seq).build());
        return event instanceof AgentStreamEvent.Complete
                || event instanceof AgentStreamEvent.Paused
                || event instanceof AgentStreamEvent.Error;
    }

    @Override
    public boolean isActive(String conversationId) {
        return Boolean.TRUE.equals(redis.opsForHash().hasKey(ACTIVE_KEY, conversationId));
    }

    @Override
    public Set<String> activeConversationIds() {
        Set<String> ids = new HashSet<>();
        for (Object field : redis.opsForHash().keys(ACTIVE_KEY)) {
            ids.add(String.valueOf(field));
        }
        return ids;
    }

    @Override
    public Long ownerOf(String conversationId) {
        Object owner = redis.opsForHash().get(ACTIVE_KEY, conversationId);
        return owner == null ? null : Long.parseLong(String.valueOf(owner));
    }

    /**
     * PauseState 是私有构造器 + Builder，且 messages 是完整消息链（大且含接口类型），
     * Jackson 直接反序列化会失败。写入前已瘦身（messages 置空），读取用 Builder 按标量字段还原。
     */
    private static ObjectMapper buildMapper() {
        SimpleModule module = new SimpleModule();
        module.addDeserializer(PauseState.class, new StdDeserializer<>(PauseState.class) {
            @Override
            public PauseState deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                JsonNode n = p.readValueAsTree();
                PauseState.Builder b = PauseState.builder()
                        .currentRound(n.path("currentRound").asInt())
                        .sessionId(n.path("sessionId").asLong())
                        .interruptedAt(n.path("interruptedAt").asLong())
                        .totalPromptTokens(n.path("totalPromptTokens").asLong())
                        .totalCompletionTokens(n.path("totalCompletionTokens").asLong());
                String query = textOrNull(n, "query");
                if (query != null) b.query(query);
                String msg = textOrNull(n, "interruptMessage");
                if (msg != null) b.interruptMessage(msg);
                String reason = textOrNull(n, "reason");
                if (reason != null) {
                    try { b.reason(PauseReason.valueOf(reason)); } catch (IllegalArgumentException ignored) { }
                }
                String safePoint = textOrNull(n, "safePoint");
                if (safePoint != null) {
                    try { b.safePoint(SafePoint.valueOf(safePoint)); } catch (IllegalArgumentException ignored) { }
                }
                return b.build();
            }
        });
        return new ObjectMapper()
                .registerModule(module)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * Paused 事件瘦身：跨节点续传只需要 reason/sessionId 等标量，messages（完整消息链）
     * 体积大且含接口类型，置空后 JSON 才能安全往返。
     */
    private AgentStreamEvent slim(AgentStreamEvent event) {
        if (event instanceof AgentStreamEvent.Paused p && p.state() != null) {
            PauseState s = p.state();
            PauseState slim = PauseState.builder()
                    .currentRound(s.getCurrentRound())
                    .query(s.getQuery())
                    .sessionId(s.getSessionId())
                    .reason(s.getReason())
                    .safePoint(s.getSafePoint())
                    .interruptMessage(s.getInterruptMessage())
                    .interruptedAt(s.getInterruptedAt())
                    .totalPromptTokens(s.getTotalPromptTokens())
                    .totalCompletionTokens(s.getTotalCompletionTokens())
                    .build();
            return new AgentStreamEvent.Paused(slim, p.source());
        }
        return event;
    }

    private String toJson(AgentStreamEvent event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[agentx-console] 事件序列化失败（跳过该条）: {}", e.getMessage());
            return null;
        }
    }

    private AgentStreamEvent fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<AgentStreamEvent>() { });
        } catch (Exception e) {
            log.warn("[agentx-console] 事件反序列化失败: {}", e.getMessage());
            return null;
        }
    }
}
