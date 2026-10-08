package com.agentx.tracer.service;

import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.domain.entities.AgentxConversation;
import com.agentx.tracer.domain.entities.AgentxFile;
import com.agentx.tracer.domain.entities.AgentxSession;
import com.agentx.tracer.domain.vo.ConversationDetailVO;
import com.agentx.tracer.domain.vo.ConversationPage;
import com.agentx.tracer.domain.vo.ConversationVO;
import com.agentx.tracer.domain.vo.SessionMessageVO;
import com.agentx.tracer.mapper.AgentxConversationMapper;
import com.agentx.tracer.mapper.AgentxSessionMapper;
import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话管理服务。
 *
 * <p>v1.0.1：agentx_conversation 记录每次调用边界，agentx_session 按 state_key 存储消息链。
 * 会话列表从 agentx_conversation 聚合，时间线从 original_messages 重构。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    private static final int TITLE_MAX_LENGTH = 40;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final AgentxConversationMapper conversationMapper;
    private final AgentxSessionMapper sessionMapper;
    private final FileManageService fileManageService;
    private final AuthService authService;
    private final LiveStreamRegistry liveStreams;

    /**
     * 列出会话（按最近活跃倒序，按 conversationId 聚合，分页返回）。
     */
    public ConversationPage listConversations(Integer page, Integer size) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : size;

        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return ConversationPage.empty(p, s);
        }

        List<AgentxConversation> all = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentxConversation>()
                        .eq(AgentxConversation::getUserId, currentUserId)
                        .orderByDesc(AgentxConversation::getCreatedAt)
        );
        if (all.isEmpty()) {
            return ConversationPage.empty(p, s);
        }

        // groupingBy 保持插入顺序 = 最近活跃的会话排前面
        Map<String, List<AgentxConversation>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        AgentxConversation::getConversationId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        // 进行中的流一次取全，避免列表逐会话查询（redis 模式下是逐次网络往返）
        Set<String> activeIds = liveStreams.activeConversationIds();

        List<ConversationVO> allConvs = grouped.entrySet().stream().map(e -> {
            List<AgentxConversation> rounds = e.getValue();
            AgentxConversation latest = rounds.get(0);
            AgentxConversation first = rounds.get(rounds.size() - 1);
            return new ConversationVO(
                    e.getKey(),
                    truncate(first.getQuestion()),
                    rounds.size(),
                    first.getCreatedAt(),
                    latest.getCreatedAt(),
                    activeIds.contains(e.getKey())
            );
        }).toList();

        int total = allConvs.size();
        int from = Math.min(p * s, total);
        int to = Math.min(from + s, total);
        boolean hasMore = to < total;
        return new ConversationPage(allConvs.subList(from, to), total, p, s, hasMore);
    }

    /**
     * 查询会话详情（包含所有问答轮次，按时间正序）。
     */
    public ConversationDetailVO getConversation(String conversationId) {
        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return null;
        }

        List<AgentxConversation> rounds = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentxConversation>()
                        .eq(AgentxConversation::getConversationId, conversationId)
                        .eq(AgentxConversation::getUserId, currentUserId)
                        .orderByAsc(AgentxConversation::getCreatedAt)
        );
        if (rounds.isEmpty()) {
            return null;
        }

        // 批量加载：一次取全会话的 original_messages 和附件，避免逐轮 N+1 查询
        Map<String, List<Message>> messagesBySessionId = loadOriginalMessagesByConversation(conversationId);
        Map<Long, List<SessionMessageVO.AttachmentInfo>> attachmentsBySessionId = loadAttachmentsByRounds(rounds);

        List<SessionMessageVO> messages = rounds.stream().map(conv -> {
            Long sessionId = parseLongSafe(conv.getSessionId());
            List<Message> originalMessages = messagesBySessionId.getOrDefault(conv.getSessionId(), List.of());
            List<SessionMessageVO.AttachmentInfo> attachments = attachmentsBySessionId.getOrDefault(sessionId, List.of());
            return new SessionMessageVO(sessionId, conv.getQuestion(), extractAnswer(originalMessages),
                    buildTimeline(originalMessages), conv.getCreatedAt(), attachments);
        }).toList();

        return new ConversationDetailVO(conversationId, messages);
    }

    /**
     * 一次查询加载整个会话的 original_messages，按 sessionId 分组。
     */
    private Map<String, List<Message>> loadOriginalMessagesByConversation(String conversationId) {
        List<AgentxSession> rows = sessionMapper.selectList(
                new LambdaQueryWrapper<AgentxSession>()
                        .eq(AgentxSession::getConversationId, conversationId)
                        .eq(AgentxSession::getStateKey, "original_messages")
                        .orderByAsc(AgentxSession::getSessionId)
                        .orderByAsc(AgentxSession::getItemIndex)
        );
        Map<String, List<Message>> result = new LinkedHashMap<>();
        for (AgentxSession row : rows) {
            if (row.getStateData() != null && !row.getStateData().isBlank()) {
                result.computeIfAbsent(row.getSessionId(), k -> new ArrayList<>())
                        .addAll(MessageJsonSerializer.fromJson(row.getStateData()));
            }
        }
        return result;
    }

    /**
     * 一次查询批量加载全会话的附件，按 sessionId 分组。
     */
    private Map<Long, List<SessionMessageVO.AttachmentInfo>> loadAttachmentsByRounds(List<AgentxConversation> rounds) {
        List<Long> sessionIds = rounds.stream()
                .map(AgentxConversation::getSessionId)
                .filter(Objects::nonNull)
                .map(this::parseLongSafe)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SessionMessageVO.AttachmentInfo>> result = new LinkedHashMap<>();
        for (AgentxFile f : fileManageService.listBySessionIds(sessionIds)) {
            result.computeIfAbsent(f.getSessionId(), k -> new ArrayList<>())
                    .add(new SessionMessageVO.AttachmentInfo(
                            f.getFileId(), f.getFileName(), f.getFileType(), f.getFileSize()));
        }
        return result;
    }

    private Long parseLongSafe(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 从消息链中提取最终回答：跳过末尾的收尾性短文本（如「任务已完成」），取第一个实质性报告。
     */
    private String extractAnswer(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String lastNonBlank = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof AssistantMessage am) {
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    if (lastNonBlank.isEmpty()) {
                        lastNonBlank = text;
                    }
                    if (isSubstantial(text)) {
                        return text;
                    }
                }
            }
        }
        return lastNonBlank;
    }

    /**
     * 实质性报告判定：够长，或带 markdown 结构（标题/表格/链接）。
     */
    private static boolean isSubstantial(String text) {
        if (text.length() >= 100) {
            return true;
        }
        return text.contains("#") || text.contains("|") || text.contains("http");
    }

    /**
     * 从消息链重构前端时间线 JSON。
     * AssistantMessage 拆为三阶段：思考(reasoning_content) → 正文(content) → 工具调用。
     */
    private String buildTimeline(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> events = new ArrayList<>();
        Set<String> seenToolCallIds = new LinkedHashSet<>();

        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                // 错误消息（LLM 调用失败）：直接作为 error 事件，不再拆 thinking/text/tool
                if ("error".equalsIgnoreCase(extractErrorType(am))) {
                    Map<String, Object> errorEvent = new LinkedHashMap<>();
                    errorEvent.put("type", "error");
                    errorEvent.put("message",
                            am.getText() != null && !am.getText().isBlank() ? am.getText() : "LLM 调用失败");
                    String detail = extractErrorDetail(am);
                    if (detail != null && !detail.isBlank()) {
                        errorEvent.put("detail", detail);
                    }
                    events.add(errorEvent);
                    continue;
                }
                // 1. 思考过程 (reasoning_content)
                String reasoning = extractReasoning(am);
                if (reasoning != null && !reasoning.isBlank()) {
                    events.add(Map.of("type", "thinking", "content", reasoning));
                }
                // 2. 正文回答
                String text = am.getText();
                if (text != null && !text.isBlank()) {
                    events.add(Map.of("type", "text", "content", text));
                }
                // 3. 工具调用（TodoWrite → todo 执行计划面板，其余 → 普通 tool 卡片）
                List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
                if (toolCalls != null) {
                    for (AssistantMessage.ToolCall tc : toolCalls) {
                        if ("TodoWrite".equalsIgnoreCase(tc.name())) {
                            List<Map<String, Object>> items = parseTodoItems(tc.arguments());
                            if (!items.isEmpty()) {
                                events.add(Map.of("type", "todo", "items", (Object) items));
                            }
                            continue;
                        }
                        seenToolCallIds.add(tc.id());
                        Map<String, Object> toolEvent = new LinkedHashMap<>();
                        toolEvent.put("type", "tool");
                        toolEvent.put("toolName", tc.name());
                        toolEvent.put("toolCallId", tc.id());
                        toolEvent.put("arguments", tc.arguments());
                        toolEvent.put("status", "pending");
                        events.add(toolEvent);
                    }
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    if (seenToolCallIds.contains(tr.id())) {
                        for (int i = events.size() - 1; i >= 0; i--) {
                            Map<String, Object> event = events.get(i);
                            if ("tool".equals(event.get("type"))
                                    && tr.id().equals(event.get("toolCallId"))
                                    && "pending".equals(event.get("status"))) {
                                event.put("result", tr.responseData());
                                event.put("status", "completed");
                                break;
                            }
                        }
                    }
                }
            }
        }

        try {
            return objectMapper.writeValueAsString(events);
        } catch (Exception e) {
            log.warn("Failed to serialize timeline: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 将 TodoWrite 参数中的 todos 转为前端 todo 面板所需的 items 格式。
     * 参数格式：{"todos": [{"content": "...", "status": "pending", "activeForm": "..."}]}
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseTodoItems(String arguments) {
        try {
            Map<String, Object> args = objectMapper.readValue(arguments, Map.class);
            List<Map<String, Object>> todos = (List<Map<String, Object>>) args.get("todos");
            if (todos == null) return List.of();
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> todo : todos) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("text", todo.getOrDefault("content", ""));
                item.put("status", todo.getOrDefault("status", "pending"));
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("Failed to parse TodoWrite arguments: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 AssistantMessage metadata 提取 reasoning_content（兼容两种 key）。
     */
    private String extractReasoning(AssistantMessage am) {
        Map<String, Object> metadata = am.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object rc = metadata.get("reasoningContent");
        if (rc == null) {
            rc = metadata.get("reasoning_content");
        }
        return rc instanceof String s ? s : null;
    }

    /**
     * 从 AssistantMessage metadata 提取 errorType（错误消息标记）。
     */
    private String extractErrorType(AssistantMessage am) {
        Map<String, Object> metadata = am.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object et = metadata.get("errorType");
        return et instanceof String s ? s : null;
    }

    /**
     * 从 AssistantMessage metadata 提取 errorDetail（LLM 失败详情）。
     */
    private String extractErrorDetail(AssistantMessage am) {
        Map<String, Object> metadata = am.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object detail = metadata.get("errorDetail");
        return detail instanceof String s ? s : null;
    }

    /**
     * 删除会话（仅当前用户自己的）。
     */
    public boolean deleteConversation(String conversationId) {
        String currentUserId = currentUserIdOrNull();
        if (currentUserId == null) {
            return false;
        }
        int deleted = conversationMapper.delete(
                new LambdaQueryWrapper<AgentxConversation>()
                        .eq(AgentxConversation::getConversationId, conversationId)
                        .eq(AgentxConversation::getUserId, currentUserId)
        );
        sessionMapper.delete(
                new LambdaQueryWrapper<AgentxSession>()
                        .eq(AgentxSession::getConversationId, conversationId)
        );
        log.info("[session] 删除会话: userId={}, conversationId={}, 调用数={}",
                currentUserId, conversationId, deleted);
        return deleted > 0;
    }

    private String currentUserIdOrNull() {
        Long uid = authService.getCurrentUserIdOrNull();
        return uid == null ? null : uid.toString();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > TITLE_MAX_LENGTH
                ? text.substring(0, TITLE_MAX_LENGTH) + "..."
                : text;
    }
}
