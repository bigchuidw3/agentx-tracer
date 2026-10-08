package com.agentx.tracer.service;

import com.agentx.tracer.domain.entities.AgentxConversation;
import com.agentx.tracer.domain.entities.AgentxSession;
import com.agentx.tracer.domain.entities.AgentxTrace;
import com.agentx.tracer.mapper.AgentxConversationMapper;
import com.agentx.tracer.mapper.AgentxSessionMapper;
import com.agentx.tracer.mapper.AgentxTraceMapper;
import com.agentx.tracer.mapper.AgentxTraceMapper.TraceRoundSummary;
import com.agentx.ai.core.utils.MessageJsonSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * trace 查询服务：诊断 Agent 三个查询工具的底层实现。
 *
 * <p>设计原则：模拟人类排查问题的操作。工具一查全貌（轮次概要 + 消息截断预览，省略模型思考）、
 * 工具二查某轮（trace 完整入参出参原文）、工具三查首现（关键词反查）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceQueryService {

    private static final String STATE_KEY_ORIGINAL = "original_messages";
    /** 消息段落角色：模型思考（segments 里使用，首现命中时按它给提示） */
    private static final String ROLE_REASONING = "assistant 思考";
    /** 首现命中时返回的关键词前后上下文长度 */
    private static final int SNIPPET_CONTEXT_CHARS = 120;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AgentxTraceMapper traceMapper;
    private final AgentxSessionMapper sessionMapper;
    private final AgentxConversationMapper conversationMapper;

    /**
     * 工具一：查全程（按 conversation_id）。
     * 返回窗口内每次对话的概要、轮次异常概要（失败轮及报错、上下文压缩点，正常轮不列）、
     * 以及消息记录的截断预览（按 item_index 顺序，标注归属 session；只渲染 content 与
     * tool_call/tool_result，省略 reasoning_content）。完整原文按 session+round 用 roundDetail 深查。
     */
    public String overview(String conversationId, String userId) {
        List<AgentxConversation> convs = conversationMapper.selectList(
                new LambdaQueryWrapper<AgentxConversation>()
                        .eq(AgentxConversation::getConversationId, conversationId)
                        .eq(AgentxConversation::getUserId, userId)
                        .orderByAsc(AgentxConversation::getCreatedAt));
        List<AgentxSession> rows = sessionMapper.selectList(
                new LambdaQueryWrapper<AgentxSession>()
                        .eq(AgentxSession::getConversationId, conversationId)
                        .eq(AgentxSession::getUserId, userId)
                        .eq(AgentxSession::getStateKey, STATE_KEY_ORIGINAL)
                        .orderByAsc(AgentxSession::getItemIndex));
        if (convs.isEmpty() && rows.isEmpty()) {
            return "未找到 conversation_id=" + conversationId
                    + " 的任何记录（会话不存在或已被删除）。请核对 conversation_id。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 执行过程全貌 conversation_id=").append(conversationId).append('\n');

        sb.append("\n## 窗口内对话（每次提问一行）\n");
        for (AgentxConversation c : convs) {
            sb.append("- session ").append(c.getSessionId())
                    .append(" | 用户 ").append(c.getUserId())
                    .append(" | 状态 ").append(c.getStatus())
                    .append(" | 问题：").append(oneLine(c.getQuestion())).append('\n');
        }

        sb.append("\n## 轮次概览（只列失败轮与上下文压缩点，未列出的轮次均成功）\n");
        List<TraceRoundSummary> rounds = traceMapper.selectRoundSummaries(conversationId, userId);
        Map<String, List<TraceRoundSummary>> bySession = new LinkedHashMap<>();
        for (TraceRoundSummary r : rounds) {
            bySession.computeIfAbsent(String.valueOf(r.getSessionId()), k -> new ArrayList<>()).add(r);
        }
        if (bySession.isEmpty()) {
            sb.append("（无 trace 记录）\n");
        }
        for (Map.Entry<String, List<TraceRoundSummary>> e : bySession.entrySet()) {
            String anomalies = roundAnomalies(e.getValue());
            sb.append("session ").append(e.getKey()).append(" 共 ").append(e.getValue().size()).append(" 轮：")
                    .append(anomalies.isEmpty() ? "全部成功，无上下文压缩\n" : "\n").append(anomalies);
        }

        sb.append("\n## 消息记录预览（正文截断、模型思考省略，assistant 行标注轮次；"
                + "完整原文按 session+round 用 traceRound 深查）\n");
        String lastSession = null;
        List<Integer> okRounds = List.of();
        int[] assistantSeq = new int[1];
        for (AgentxSession row : rows) {
            String sid = row.getSessionId();
            if (!sid.equals(lastSession)) {  // session 边界：换成功轮表，轮次计数归零
                lastSession = sid;
                okRounds = new ArrayList<>();
                for (TraceRoundSummary r : bySession.getOrDefault(sid, List.of())) {
                    if (isSuccess(r)) {
                        okRounds.add(r.getRound());
                    }
                }
                assistantSeq[0] = 0;
                sb.append("\n【session ").append(sid).append("】\n");
            }
            sb.append(previewMessages(row.getStateData(), row.getItemIndex(), okRounds, assistantSeq));
        }
        return sb.toString();
    }

    /**
     * 消息预览：每条消息一行（行首标 item 序号），只渲染 content 截断行与
     * tool_call/tool_result 截断行，省略 reasoning_content。assistant 行标注对应轮次：
     * 第 N 个 assistant 消息取成功轮列表里的第 N 个 round。通读定位用；
     * 完整原文（含模型思考）用 roundDetail。
     */
    private static String previewMessages(String stateData, int itemIndex,
                                          List<Integer> successRounds, int[] assistantSeq) {
        List<Message> messages;
        try {
            messages = MessageJsonSerializer.fromJson(stateData);
        } catch (Exception e) {
            return "item " + itemIndex + " | " + oneLine(stateData) + '\n';  // 解析失败退化为单行截断，不中断通读
        }
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            if (msg instanceof AssistantMessage am) {
                int seq = assistantSeq[0]++;
                String roundTag = seq < successRounds.size()
                        ? "（round " + successRounds.get(seq) + "）" : "";
                String text = am.getText();
                List<AssistantMessage.ToolCall> calls = am.getToolCalls();
                // 标签行始终输出（纯工具调用轮正文为空，也要能看到轮次），正文有才追加
                sb.append("item ").append(itemIndex).append(" | assistant").append(roundTag).append("：");
                if (text != null && !text.isBlank()) {
                    sb.append(oneLine(text));
                }
                sb.append('\n');
                if (calls != null) {
                    for (AssistantMessage.ToolCall tc : calls) {
                        sb.append("        调用 ").append(tc.name())
                                .append("(").append(oneLine(tc.arguments())).append(")\n");
                    }
                }
            } else if (msg instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                    sb.append("item ").append(itemIndex).append(" |   结果 ").append(tr.name()).append("：")
                            .append(oneLine(String.valueOf(tr.responseData()))).append('\n');
                }
            } else if (msg.getText() != null && !msg.getText().isBlank()) {
                sb.append("item ").append(itemIndex).append(" | ")
                        .append(String.valueOf(msg.getMessageType()).toLowerCase())
                        .append("：").append(oneLine(msg.getText())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 工具二：查一轮（按 session_id + round 查 agentx_trace）。
     * 返回该轮 input_data/output_data。input_data 默认省略 tools 工具定义
     * （各轮相同且篇幅大），includeTools=true 才附带。失败的调用只有这里能看到。
     */
    public String roundDetail(String sessionId, Integer round, Boolean includeTools, String userId) {
        Long sid = parseSessionId(sessionId);
        if (sid == null || round == null || round < 1) {
            return "参数错误：sessionId 必须是数字字符串（会话概览里的 session 编号），round 从 1 开始。";
        }
        AgentxTrace t = traceMapper.selectOne(
                new LambdaQueryWrapper<AgentxTrace>()
                        .eq(AgentxTrace::getSessionId, sid)
                        .eq(AgentxTrace::getUserId, userId)
                        .eq(AgentxTrace::getRound, round)
                        .last("LIMIT 1"));
        if (t == null) {
            return "未找到 session_id=" + sid + " round=" + round
                    + " 的 trace 记录。请核对轮次概览里的 session 编号与轮次。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# 轮次详情 session_id=").append(sid).append(" round=").append(round).append('\n');
        sb.append("耗时 ").append(t.getDurationMs()).append("ms");
        if (t.getSuccess() != null && t.getSuccess() == 0) {
            sb.append(" | 失败 | 错误：").append(oneLine(t.getErrorMessage()));
        } else {
            sb.append(" | 成功");
        }
        sb.append("\n\n## input_data（本轮发给模型的完整请求，tools 定义默认省略）\n")
                .append(renderInputData(t.getInputData(), Boolean.TRUE.equals(includeTools)))
                .append("\n\n## output_data（模型本轮输出原文）\n")
                .append(t.getOutputData())
                .append('\n');
        return sb.toString();
    }

    /**
     * 渲染 input_data：messages 与顶层参数始终保留，tools 工具定义默认省略并附提示行，
     * includeTools=true 时单独附在末尾。解析失败退回原文。
     */
    private static String renderInputData(String inputData, boolean includeTools) {
        try {
            JsonNode root = JSON.readTree(inputData);
            ObjectNode out = (ObjectNode) root;
            JsonNode tools = out.remove("tools");
            String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out);
            if (tools == null) {
                return json;
            }
            return includeTools
                    ? json + "\n\n## tools（工具定义）\n"
                            + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tools)
                    : json + "\n（tools 工具定义已省略：各轮相同且篇幅大；需核对工具 schema 时传 includeTools=true 重查）";
        } catch (Exception e) {
            return inputData;
        }
    }

    /**
     * 工具三：查首现（按 conversation_id + keyword 查消息记录）。
     * original_messages 按 item_index 逐条扫描，命中即停，
     * 返回第一次出现的位置（item_index、session、消息角色）和首现位置前后各约 120 字符的原文片段。
     */
    public String firstAppear(String conversationId, String keyword, String userId) {
        if (keyword == null || keyword.isBlank()) {
            return "参数错误：keyword 不能为空。";
        }
        String kw = keyword.trim();
        List<AgentxSession> rows = sessionMapper.selectList(
                new LambdaQueryWrapper<AgentxSession>()
                        .eq(AgentxSession::getConversationId, conversationId)
                        .eq(AgentxSession::getUserId, userId)
                        .eq(AgentxSession::getStateKey, STATE_KEY_ORIGINAL)
                        .orderByAsc(AgentxSession::getItemIndex));
        for (AgentxSession row : rows) {
            if (row.getStateData() == null || row.getStateData().isBlank()) {
                continue;
            }
            List<Message> messages;
            try {
                messages = MessageJsonSerializer.fromJson(row.getStateData());
            } catch (Exception e) {
                log.warn("[trace-diag] 首现检索跳过解析失败的行 item={} err={}",
                        row.getItemIndex(), e.getMessage());
                continue;
            }
            for (Message msg : messages) {
                for (Segment seg : segments(msg)) {
                    int idx = seg.text().indexOf(kw);
                    if (idx >= 0) {
                        String hit = "关键词「" + kw + "」首次出现在 item_index=" + row.getItemIndex()
                                + " | session " + row.getSessionId()
                                + " | " + seg.role()
                                + "\n原文片段（首现位置前后）：\n" + snippetAround(seg.text(), idx, kw.length());
                        if (ROLE_REASONING.equals(seg.role())) {
                            hit += "\n（提示：命中模型思考，说明该措辞是模型推导出的；"
                                    + "若要定位数据源头，可换工具返回的原始特征值如字段名、原始数值、人名再查）";
                        }
                        return hit;
                    }
                }
            }
        }
        return "关键词「" + kw + "」在 conversation_id=" + conversationId
                + " 的消息记录中未出现。可换一个更短或更关键的特征值再试。";
    }

    // ── 消息可检索段落：正文 / 思考 / 工具调用 / 工具结果 ──

    private record Segment(String role, String text) {
    }

    private static List<Segment> segments(Message msg) {
        List<Segment> out = new ArrayList<>();
        if (msg instanceof AssistantMessage am) {
            String reasoning = extractReasoning(am);
            if (reasoning != null && !reasoning.isBlank()) {
                out.add(new Segment(ROLE_REASONING, reasoning));
            }
            String text = am.getText();
            if (text != null && !text.isBlank()) {
                out.add(new Segment("assistant 正文", text));
            }
            List<AssistantMessage.ToolCall> toolCalls = am.getToolCalls();
            if (toolCalls != null) {
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    out.add(new Segment("assistant 工具调用 " + tc.name(), tc.arguments()));
                }
            }
        } else if (msg instanceof ToolResponseMessage trm) {
            for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                out.add(new Segment("工具结果 " + tr.name(), String.valueOf(tr.responseData())));
            }
        } else if (msg.getText() != null && !msg.getText().isBlank()) {
            out.add(new Segment(String.valueOf(msg.getMessageType()).toLowerCase(), msg.getText()));
        }
        return out;
    }

    /** 从 AssistantMessage metadata 提取 reasoning_content（兼容两种 key）。 */
    private static String extractReasoning(AssistantMessage am) {
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

    // ── 小工具 ──

    /**
     * 异常轮清单：失败轮（附报错）与压缩点（input 字符量低于上一轮 = 之前发生过上下文压缩）。
     * 正常轮不列，通读时看消息预览即可。
     */
    private static String roundAnomalies(List<TraceRoundSummary> rounds) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rounds.size(); i++) {
            TraceRoundSummary r = rounds.get(i);
            if (!isSuccess(r)) {
                sb.append("- round ").append(r.getRound()).append(" | 失败");
                if (r.getErrorMessage() != null && !r.getErrorMessage().isBlank()) {
                    sb.append(" | 错误：").append(oneLine(r.getErrorMessage()));
                }
                sb.append('\n');
            } else if (i > 0 && isCharDrop(rounds.get(i - 1), r)) {
                sb.append("- round ").append(r.getRound()).append(" | input 字符量 ")
                        .append(rounds.get(i - 1).getInputChars()).append("→").append(r.getInputChars())
                        .append(" 骤降，之前发生过上下文压缩\n");
            }
        }
        return sb.toString();
    }

    private static boolean isCharDrop(TraceRoundSummary prev, TraceRoundSummary cur) {
        return prev.getInputChars() != null && cur.getInputChars() != null
                && cur.getInputChars() < prev.getInputChars();
    }

    private static boolean isSuccess(TraceRoundSummary r) {
        return r.getSuccess() != null && r.getSuccess() == 1;
    }

    private static Long parseSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sessionId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String oneLine(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    private static String snippetAround(String text, int idx, int kwLen) {
        int start = Math.max(0, idx - SNIPPET_CONTEXT_CHARS);
        int end = Math.min(text.length(), idx + kwLen + SNIPPET_CONTEXT_CHARS);
        StringBuilder sb = new StringBuilder();
        if (start > 0) {
            sb.append("...");
        }
        sb.append(text, start, end);
        if (end < text.length()) {
            sb.append("...");
        }
        return sb.toString().replace("\r", " ").replace("\n", " ");
    }
}
