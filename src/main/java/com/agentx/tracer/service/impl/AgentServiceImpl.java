package com.agentx.tracer.service.impl;

import com.agentx.ai.core.agent.ReactAgent;
import com.agentx.ai.core.agent.internal.AgentTaskManager;
import com.agentx.ai.core.observability.TracingHook;
import com.agentx.ai.core.context.ContextPolicy;
import com.agentx.ai.core.interrupt.JdbcPauseStateStore;
import com.agentx.ai.core.model.AgentStreamEvent;
import com.agentx.ai.core.model.RunnableParams;
import com.agentx.ai.core.model.ThinkingMode;
import com.agentx.ai.core.sandbox.IsolationScope;
import com.agentx.ai.core.sandbox.SandboxConfig;
import com.agentx.ai.core.sandbox.docker.DockerBackend;
import com.agentx.ai.core.tools.BashTool;
import com.agentx.ai.core.tools.FileSystemTools;
import com.agentx.ai.core.tools.GrepTool;
import com.agentx.ai.core.tools.SkillsTool;
import com.agentx.ai.core.tools.TodoWriteTool;
import com.agentx.ai.core.tools.toolsearch.ToolSearchConfig;
import com.agentx.tracer.chat.ChatModelProvider;
import com.agentx.tracer.domain.entities.AgentxFile;
import com.agentx.tracer.hook.BudgetAlertHook;
import com.agentx.tracer.prompt.SystemPrompt;
import com.agentx.tracer.service.AgentService;
import com.agentx.tracer.service.FileManageService;
import com.agentx.tracer.service.SandboxConfigService;
import com.agentx.tracer.skill.SkillManager;
import com.agentx.tracer.tools.FileTool;
import com.agentx.tracer.tools.TimeTool;
import com.agentx.tracer.tools.TraceQueryTools;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.agentx.ai.core.utils.ToolMergeUtil.mergeTools;

/**
 * 主智能体装配。
 *
 * <p>工具分层装配：
 * <ul>
 *   <li>常驻工具（always-on）：TodoWrite / FileSystem / Grep / currentTime / SkillsTool —— 每次请求加载</li>
 *   <li>延迟工具（deferred）：trace 查询 —— 通过 ToolSearchTool 按需发现</li>
 * </ul>
 *
 * <p>安全场景能力由 Skills 承载（skills 目录 + SkillsTool 按需加载 SKILL.md），
 * 场景扩展不改代码、只增技能，符合"可复用智能体"定位。
 *
 * <p>单例部分（@PostConstruct 一次性构建）：ChatModel（经 {@link ChatModelProvider} 供给）、
 * alwaysOnBaseTools、deferredTools、ContextPolicy。
 * 每次请求重建：SkillsTool（从 DB 查 enabled skills → 扫描目录）。
 * 共享单例：AgentTaskManager（stop 可寻址运行中任务）、JdbcPauseStateStore（中断快照）。
 *
 * @author agentx-console
 */
@Slf4j
@Service
public class AgentServiceImpl implements AgentService {

    private static final int TOOL_SEARCH_MAX_RESULTS = 10;

    private final DataSource dataSource;
    private final SkillManager skillManager;
    private final TimeTool timeTool;
    private final TraceQueryTools traceQueryTools;
    private final FileManageService fileManageService;
    private final ChatModelProvider chatModelProvider;
    private final BudgetAlertHook budgetAlertHook;
    private final FileTool fileTool;
    private final Tracer tracer;
    private final SandboxConfigService sandboxConfigService;

    /** skills 根目录（无启用技能时 SkillsTool 兜底扫描用） */
    @Value("${skills.directory}")
    private String skillsRootDir;

    /** 工具沙箱根目录（FileSystem/Grep 只允许访问 workspace 和 skills） */
    @Value("${app.workspace-dir:./workspace}")
    private String workspaceDir;

    /** 宿主机 workspace 绝对路径（沙箱容器 bind mount 用；为空则不挂载） */
    @Value("${app.workspace-host-dir:}")
    private String workspaceHostDir;

    /** 宿主机 skills 绝对路径（沙箱容器 bind mount 用；为空则不挂载） */
    @Value("${app.skills-host-dir:}")
    private String skillsHostDir;

    /** 沙箱容器镜像 */
    @Value("${app.sandbox-image:ubuntu:22.04}")
    private String sandboxImage;

    /** 沙箱容器网络：true=开通（bridge），false=禁用 */
    @Value("${app.sandbox-network-enabled:true}")
    private boolean sandboxNetworkEnabled;

    private ToolCallback[] alwaysOnBaseTools;
    private ToolCallback[] deferredTools;
    private ContextPolicy contextPolicy;
    private final ToolSearchConfig toolSearchConfig =
            ToolSearchConfig.builder().maxResults(TOOL_SEARCH_MAX_RESULTS).build();

    private final AgentTaskManager sharedTaskManager = new AgentTaskManager();
    private final JdbcPauseStateStore sharedStateStore;

    public AgentServiceImpl(DataSource dataSource,
                        SkillManager skillManager,
                        TimeTool timeTool,
                        TraceQueryTools traceQueryTools,
                        FileManageService fileManageService,
                        ChatModelProvider chatModelProvider,
                        BudgetAlertHook budgetAlertHook,
                        FileTool fileTool,
                        Tracer tracer,
                        SandboxConfigService sandboxConfigService) {
        this.dataSource = dataSource;
        this.skillManager = skillManager;
        this.timeTool = timeTool;
        this.traceQueryTools = traceQueryTools;
        this.fileManageService = fileManageService;
        this.chatModelProvider = chatModelProvider;
        this.budgetAlertHook = budgetAlertHook;
        this.fileTool = fileTool;
        this.tracer = tracer;
        this.sandboxConfigService = sandboxConfigService;
        this.sharedStateStore = new JdbcPauseStateStore(dataSource);
    }

    @PostConstruct
    void init() {
        this.alwaysOnBaseTools = buildAlwaysOnBaseTools();
        this.deferredTools = buildDeferredTools();
        this.contextPolicy = buildContextPolicy();
        this.sharedStateStore.initialize();
        log.info("[agentx-console] AgentServiceImpl 初始化完成 | 常驻={} | deferred={}",
                alwaysOnBaseTools.length, deferredTools.length);
    }

    /**
     * 流式调用。按 userId + 会话文件构造 instructions。
     */
    public Flux<AgentStreamEvent> streamForResult(String query, RunnableParams params,
                                                  List<String> fileIds) {
        String convId = params.getConversationId();

        // 本次新上传的文件立即关联到会话，让本轮 system prompt 能反查到
        if (fileIds != null && !fileIds.isEmpty()) {
            fileManageService.linkToConversation(fileIds, convId);
        }

        long userId = parseUserId(params.getUserId());
        String instructions = buildInstructions(convId, fileIds, userId);
        ReactAgent agent = buildReactAgent(instructions, userId);

        Flux<AgentStreamEvent> baseFlux = agent.streamForResult(query, params);

        // session_id 仍在 Complete 事件后补写（历史回放按轮次取附件用）
        if (fileIds == null || fileIds.isEmpty()) {
            return baseFlux;
        }
        return baseFlux.doOnNext(evt -> linkFilesFromEvent(fileIds, evt));
    }

    /**
     * 用户主动中断指定会话的流式任务，持久化快照以便后续 resume。
     */
    public boolean interrupt(String conversationId) {
        return sharedTaskManager.interrupt(conversationId, "用户主动中断");
    }

    /**
     * 检查指定会话是否存在未恢复的中断状态。
     */
    public boolean hasInterruptedState(String conversationId) {
        return sharedStateStore.exists(conversationId);
    }

    /**
     * 丢弃指定会话的中断快照：新消息顶掉进行中的回答时调用。
     */
    public void discardInterruptedState(String conversationId) {
        sharedStateStore.delete(conversationId);
    }

    /**
     * 从中断状态恢复流式执行。
     *
     * @param resumeQuery 用户中断后输入的澄清/继续语句（可为空，直接恢复不追加消息）
     */
    public Flux<AgentStreamEvent> resumeStream(String conversationId,
                                               List<String> fileIds, @Nullable String resumeQuery) {
        if (!sharedStateStore.exists(conversationId)) {
            return Flux.error(new IllegalStateException(
                    "No interrupted state for conversation: " + conversationId));
        }
        if (fileIds != null && !fileIds.isEmpty()) {
            fileManageService.linkToConversation(fileIds, conversationId);
        }
        long userId = parseUserId(null);
        String instructions = buildInstructions(conversationId, fileIds, userId);
        ReactAgent agent = buildReactAgent(instructions, userId);
        return agent.resumeStream(conversationId, resumeQuery)
                .doOnNext(evt -> linkFilesFromEvent(fileIds, evt));
    }

    /**
     * 从流式事件中提取 sessionId，关联文件到 session。
     * Complete（正常完成）和 Paused（用户中断）都处理。
     */
    private void linkFilesFromEvent(List<String> fileIds, AgentStreamEvent evt) {
        if (fileIds == null || fileIds.isEmpty()) {
            return;
        }
        Long sessionId = null;
        String conversationId = null;
        if (evt instanceof AgentStreamEvent.Complete c && c.sessionId() != null) {
            sessionId = c.sessionId();
            conversationId = c.conversationId();
        } else if (evt instanceof AgentStreamEvent.Paused p
                && p.state() != null && p.state().getSessionId() > 0) {
            sessionId = p.state().getSessionId();
        }
        if (sessionId != null) {
            try {
                fileManageService.linkToSession(fileIds, sessionId, conversationId);
            } catch (Exception e) {
                log.warn("[agentx-console] link 文件失败（不影响主流程）: sessionId={}, fileIds={}",
                        sessionId, fileIds, e);
            }
        }
    }

    /**
     * 构造 instructions：系统提示 + 会话文件清单。
     */
    private String buildInstructions(String conversationId, List<String> currentFileIds, long userId) {
        String workspaceRoot = Path.of(workspaceDir).resolve(String.valueOf(userId)).normalize().toString();
        String base = SystemPrompt.build(SystemPrompt.workspaceContext(workspaceRoot));
        return appendSessionFiles(base, conversationId, currentFileIds, userId);
    }

    /**
     * 把会话文件分两组展示：本次新上传（一组）+ 历史文件（按 session_id 分多组）。
     */
    private String appendSessionFiles(String instructions, String conversationId,
                                      List<String> currentFileIds, long userId) {
        if (conversationId == null || conversationId.isBlank()) {
            return instructions;
        }
        List<AgentxFile> files = fileManageService.listByConversationId(conversationId);
        if (files.isEmpty()) {
            return instructions;
        }
        Set<String> currentSet = currentFileIds == null ? Set.of() : new LinkedHashSet<>(currentFileIds);
        List<AgentxFile> currentFiles = new ArrayList<>();
        Map<Long, List<AgentxFile>> historyBySession = new LinkedHashMap<>();
        for (AgentxFile f : files) {
            if (currentSet.contains(f.getFileId())) {
                currentFiles.add(f);
            } else {
                historyBySession.computeIfAbsent(f.getSessionId(), k -> new ArrayList<>()).add(f);
            }
        }
        int historyCount = historyBySession.values().stream().mapToInt(List::size).sum();

        List<String> parts = new ArrayList<>();
        parts.add(instructions);
        parts.add("## 当前会话文件（重要）");
        parts.add("- 读文档正文用 analyzeFile(fileId)；脚本/流量/二进制要交给 skill 检测时，用 resolveFile(fileId, fileName) 拿本地路径。");
        parts.add("");

        if (currentFiles.isEmpty()) {
            parts.add("（本次无新文件上传，仅可追问以下历史文件）");
            parts.add("");
        } else {
            parts.add("### 本次新上传（" + currentFiles.size() + " 个，是一个整体）");
            parts.add("");
            parts.add(renderGroup(currentFiles, userId));
            parts.add("");
        }

        if (historyCount > 0) {
            parts.add("### 历史文件（" + historyCount + " 个，按上传轮次分组，从旧到新）");
            parts.add("");
            int sessionIdx = 0;
            int sessionTotal = historyBySession.size();
            for (Map.Entry<Long, List<AgentxFile>> entry : historyBySession.entrySet()) {
                sessionIdx++;
                List<AgentxFile> groupFiles = entry.getValue();
                String tag = entry.getKey() == null
                        ? "未知轮次"
                        : "第 " + sessionIdx + " 轮";
                if (entry.getKey() != null && sessionTotal > 1) {
                    if (sessionIdx == 1) tag += "（最早）";
                    else if (sessionIdx == sessionTotal) tag += "（最新）";
                }
                parts.add("【" + tag + "，" + groupFiles.size() + " 个】");
                parts.add(renderGroup(groupFiles, userId));
                parts.add("");
            }
        }

        parts.add("【触发规则】");
        parts.add("- 用户说\"这个/这些/它们/总结一下/讲了什么/分析下\"等指代词或泛指时，默认指【本次新上传】整组");
        parts.add("- 用户说\"上一篇/上一个/刚才的文档\"时，默认指【历史文件】里最新一轮的全部");
        parts.add("- 用户明说某个文件名/序号时，按指定的处理");
        parts.add("- 普通闲聊（天气、写代码、打招呼）不要关联文件");

        return String.join("\n", parts);
    }

    /** 渲染一组文件列表，组内编号从 1 开始。 */
    private String renderGroup(List<AgentxFile> groupFiles, long userId) {
        return IntStream.range(0, groupFiles.size())
                .mapToObj(i -> {
                    AgentxFile f = groupFiles.get(i);
                    String localPath = fileManageService.ensureLocalCopy(f.getFileId(), f.getFileName(), userId);
                    return (i + 1) + ". fileId: " + f.getFileId()
                            + "，文件名: " + f.getFileName()
                            + "，类型: " + f.getFileType()
                            + "，本地路径: " + (localPath == null ? "（缺失，用 resolveFile 重建）" : localPath);
                })
                .collect(Collectors.joining("\n"));
    }

    /** 解析运行时 userId（缺失/非法回退 1，兼容种子 admin 账号） */
    private static long parseUserId(String s) {
        try {
            return s == null || s.isBlank() ? 1L : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 1L;
        }
    }

    private ReactAgent buildReactAgent(String instructions, long userId) {
        ToolCallback[] skillsTools = buildSkillsTools(userId);
        ToolCallback[] fsTools = buildFileSystemTools(userId);
        ToolCallback[] alwaysOn = mergeTools(alwaysOnBaseTools, fsTools, skillsTools);

        ChatModel chatModel = chatModelProvider.getChatModel(userId);
        ReactAgent.Builder builder = ReactAgent.builder()
                .chatModel(chatModel)
                .instructions(instructions)
                .dataSource(dataSource)
                .taskManager(sharedTaskManager)
                .stateStore(sharedStateStore)
                .contextPolicy(contextPolicy)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .tools(alwaysOn)
                .deferredTools(toolSearchConfig, deferredTools)
                .hooks(budgetAlertHook, new TracingHook(tracer))
                .maxRounds(100);
        // 沙箱执行：按用户开关（默认开），开启时工具在兄弟容器内执行
        if (sandboxConfigService.isEnabled(userId)) {
            builder.sandbox(SandboxConfig.builder()
                    .backend(buildDockerBackend(userId))
                    .isolationScope(IsolationScope.USER)
                    .autoRelease(false)
                    .snapshotDir(Path.of(workspaceDir).resolve("sandbox-snapshots"))
                    .build());
        }
        return builder.build();
    }

    /**
     * 构建沙箱 DockerBackend：按配置把宿主机 workspace/{userId} 和 skills 目录
     * bind mount 进沙箱容器的相同路径，实现宿主机与沙箱文件打通。
     * 宿主机路径未配置时跳过对应挂载（本地调测无 Docker 沙箱）。
     */
    private DockerBackend buildDockerBackend(long userId) {
        DockerBackend.Builder backendBuilder = DockerBackend.builder()
                .image(sandboxImage)
                .networkDisabled(!sandboxNetworkEnabled);
        if (workspaceHostDir != null && !workspaceHostDir.isBlank()) {
            backendBuilder.mount(workspaceHostDir + "/" + userId, workspaceDir + "/" + userId);
        }
        if (skillsHostDir != null && !skillsHostDir.isBlank()) {
            backendBuilder.mount(skillsHostDir, skillsRootDir);
        }
        return backendBuilder.build();
    }

    /**
     * 从 DB 查 enabled skills，构建 SkillsTool。
     * 无启用 skill 时返回空数组（不注册 SkillsTool）。
     */
    private ToolCallback[] buildSkillsTools(long userId) {
        // ToC 用户隔离：加载平台内置 + 当前用户的启用技能
        List<String> enabledDirs = skillManager.getEnabledSkillDirs(userId);
        SkillsTool.Builder builder = SkillsTool.builder();
        if (enabledDirs.isEmpty()) {
            // 无启用技能时也注册 SkillsTool（扫描根目录，空清单）：保持技能入口稳定，
            // 模型调用得到"无可用技能"即转向通用工具，避免反复 tool_search 空转
            builder.addSkillsDirectory(skillsRootDir);
        } else {
            for (String dir : enabledDirs) {
                builder.addSkillsDirectory(dir);
            }
        }
        return new ToolCallback[]{builder.build()};
    }

    /**
     * 常驻工具：内置能力，每次请求必带。
     * 包含：规划 / 文件系统 / 代码搜索 / 当前时间 / SkillsTool（每次请求重建）
     */
    private ToolCallback[] buildAlwaysOnBaseTools() {
        return mergeTools(
                TodoWriteTool.create(),
                BashTool.create(),
                ToolCallbacks.from(timeTool),
                ToolCallbacks.from(fileTool)
        );
    }

    /**
     * 按用户构建文件系统/搜索工具（沙箱隔离：用户 workspace + builtin 技能 + 本人私有技能）。
     */
    private ToolCallback[] buildFileSystemTools(long userId) {
        String userWorkspace = Path.of(workspaceDir).resolve(String.valueOf(userId))
                .toAbsolutePath().normalize().toString();
        String builtinSkills = Path.of(skillsRootDir, "builtin").toAbsolutePath().normalize().toString();
        String userSkills = Path.of(skillsRootDir, "u-" + userId).toAbsolutePath().normalize().toString();
        FileSystemTools fsTools = FileSystemTools.builder()
                .allowedDirs(userWorkspace, builtinSkills, userSkills)
                .virtualMode(true)
                .build();
        return mergeTools(
                ToolCallbacks.from(fsTools),
                GrepTool.create(userWorkspace, builtinSkills, userSkills)
        );
    }

    /**
     * 延迟工具：观测查询（trace 检索）。
     * LLM 通过 tool_search 元工具按需发现，不占常驻上下文。
     */
    private ToolCallback[] buildDeferredTools() {
        return mergeTools(ToolCallbacks.from(traceQueryTools));
    }

    private ContextPolicy buildContextPolicy() {
        return ContextPolicy.defaults();
    }
}
