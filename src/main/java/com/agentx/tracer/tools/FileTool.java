package com.agentx.tracer.tools;

import com.agentx.tracer.service.FileManageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 上传文件统一访问工具（一个组件两个方法）：
 * <ul>
 *   <li>resolveFile：拿文件本地绝对路径（按用户隔离），供脚本/检测类 skill 直接用路径读文件；
 *       本地副本缺失时自动从对象存储（MinIO）下载重建。</li>
 *   <li>analyzeFile：读文本类文档内容，供 LLM 直接分析；二进制/脚本/流量类返回路径提示走 skill。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileTool {

    private final FileManageService fileManageService;

    @Tool(name = "resolveFile", description = "解析上传文件的本地绝对路径，供脚本/检测类 skill 直接用路径读取文件。本地副本不存在时自动从对象存储下载重建。脚本、流量、二进制类文件请用本方法拿路径交给对应 skill，不要用 analyzeFile 读内容。")
    public String resolveFile(
            @ToolParam(description = "文件 ID（system prompt「当前会话文件」清单中每项对应一个 fileId）") String fileId,
            @ToolParam(description = "文件名，用于定位本地副本", required = false) String fileName,
            ToolContext toolContext) {
        if (fileId == null || fileId.isBlank()) {
            return "文件 ID 不能为空";
        }
        String path = fileManageService.ensureLocalCopy(fileId, fileName, userId(toolContext));
        if (path == null) {
            return "文件不存在或已失效（本地与对象存储均无此文件）: " + fileId;
        }
        return path;
    }

    @Tool(name = "analyzeFile", description = "读取上传文档的文本内容供分析。仅文本类文件（txt/md/csv/json/代码等）可读；脚本/流量/二进制文件请用 resolveFile 拿路径交给对应 skill。")
    public String analyzeFile(
            @ToolParam(description = "文件 ID") String fileId,
            @ToolParam(description = "文件名", required = false) String fileName,
            ToolContext toolContext) {
        if (fileId == null || fileId.isBlank()) {
            return "文件 ID 不能为空";
        }
        String path = fileManageService.ensureLocalCopy(fileId, fileName, userId(toolContext));
        if (path == null) {
            return "文件不存在或已失效: " + fileId;
        }
        try {
            Path p = Path.of(path);
            String content;
            try {
                content = Files.readString(p, StandardCharsets.UTF_8);
            } catch (Exception utf8Err) {
                content = Files.readString(p, Charset.forName("GBK"));
            }
            return content;
        } catch (Exception e) {
            log.warn("[agentx-console] analyzeFile 非文本读取失败，提示走 resolveFile: fileId={}, err={}", fileId, e.getMessage());
            return "该文件不是可读文本，请改用 resolveFile 获取本地路径交给对应 skill 处理。";
        }
    }

    /** 从 ToolContext 取当前用户 id（framework 已把 RunnableParams.userId 注入），兜底种子 admin=1。 */
    private long userId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object v = toolContext.getContext().get("userId");
            if (v != null) {
                try {
                    return Long.parseLong(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 1L;
    }
}
