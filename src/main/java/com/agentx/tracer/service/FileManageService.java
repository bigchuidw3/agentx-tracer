package com.agentx.tracer.service;

import com.agentx.tracer.domain.entities.AgentxFile;
import com.agentx.tracer.mapper.AgentxFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 文件管理服务（上传主流程编排）。
 * <p>
 * 流程：
 * 1. ensureTableExists（启动时自动建 agentx_file 表）
 * 2. 上传：生成 fileId (UUID) + 写 DB（PROCESSING）→ 上传 MinIO → 状态置 SUCCESS
 * - 失败：状态置 FAILED，抛异常
 * <p>
 * 不做上传时解析/向量化：文件内容的消费由智能体工具按需读取，
 * 保持上传链路轻量（样本分析场景后续由安全技能工具处理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileManageService {

    private static final Set<String> IMAGE_EXTS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
    );

    private final DataSource dataSource;
    private final MinioService minioService;
    private final AgentxFileMapper agentxFileMapper;

    /** 工具沙箱根目录（上传本地副本在其 uploads/ 下） */
    @Value("${app.workspace-dir:./workspace}")
    private String workspaceDir;

    @PostConstruct
    void ensureTableExists() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agentx_file (
                    id              BIGINT       NOT NULL,
                    file_id         VARCHAR(64)  NOT NULL,
                    file_name       VARCHAR(255) NOT NULL,
                    file_type       VARCHAR(32),
                    file_size       BIGINT,
                    minio_path      VARCHAR(512),
                    extracted_text  MEDIUMTEXT,
                    status          VARCHAR(16)  NOT NULL,
                    embed           TINYINT      NOT NULL DEFAULT 0,
                    conversation_id VARCHAR(64),
                    session_id      BIGINT,
                    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id),
                    UNIQUE KEY uk_file_id (file_id)
                ) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
                """);
        // 旧表升级：补加 session_id 列 + 索引（幂等：列已存在则跳过）
        tryExecute(jdbc, "ALTER TABLE agentx_file ADD COLUMN session_id BIGINT DEFAULT NULL");
        tryExecute(jdbc, "CREATE INDEX idx_agentx_file_session ON agentx_file (session_id)");
        // conversation_id 索引：stream 启动时按会话反查文件用
        tryExecute(jdbc, "CREATE INDEX idx_agentx_file_conv ON agentx_file (conversation_id)");
        log.info("[agentx-console] 表 agentx_file 已就绪");
    }

    /**
     * 静默执行 DDL（建索引 / 补列），失败不阻断启动（旧表已存在列/索引时正常跳过）。
     */
    private void tryExecute(JdbcTemplate jdbc, String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception e) {
            log.debug("[agentx-console] DDL 跳过（可能已存在）: {} | err={}", sql, e.getMessage());
        }
    }

    /**
     * 把一组文件关联到某次 agentx_session 轮次 + 所属会话。
     * 由 AgentService 在 stream Complete 事件中调用（Complete 携带 sessionId + conversationId）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void linkToSession(List<String> fileIds, Long sessionId, String conversationId) {
        if (fileIds == null || fileIds.isEmpty() || sessionId == null) {
            return;
        }
        LambdaUpdateWrapper<AgentxFile> wrapper = new LambdaUpdateWrapper<AgentxFile>()
                .in(AgentxFile::getFileId, fileIds)
                .set(AgentxFile::getSessionId, sessionId);
        if (conversationId != null && !conversationId.isBlank()) {
            wrapper.set(AgentxFile::getConversationId, conversationId);
        }
        int updated = agentxFileMapper.update(null, wrapper);
        log.info("[agentx-console] 已 link 文件到 session: sessionId={}, convId={}, files={}, updated={}",
                sessionId, conversationId, fileIds.size(), updated);
    }

    /**
     * 按 sessionId 批量查询附件元数据（历史会话回放用）。
     */
    public List<AgentxFile> listBySessionId(Long sessionId) {
        if (sessionId == null) {
            return List.of();
        }
        return agentxFileMapper.selectList(
                new LambdaQueryWrapper<AgentxFile>().eq(AgentxFile::getSessionId, sessionId));
    }

    /**
     * 按 sessionId 集合批量查询附件元数据（历史会话回放批量加载，避免逐轮 N+1）。
     */
    public List<AgentxFile> listBySessionIds(List<Long> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }
        return agentxFileMapper.selectList(
                new LambdaQueryWrapper<AgentxFile>().in(AgentxFile::getSessionId, sessionIds));
    }

    /**
     * 把一组文件关联到会话（按 conversation_id）。
     * 由 AgentService 在 streamForResult / resumeStream 启动时调用：
     * 此时 conversationId 已经确定（前端生成），立即写入让后续轮次能按 conversation_id 反查。
     * session_id 仍然在 Complete 事件后补写（用于历史回放按轮次取附件）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void linkToConversation(List<String> fileIds, String conversationId) {
        if (fileIds == null || fileIds.isEmpty()
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        LambdaUpdateWrapper<AgentxFile> wrapper = new LambdaUpdateWrapper<AgentxFile>()
                .in(AgentxFile::getFileId, fileIds)
                .set(AgentxFile::getConversationId, conversationId);
        int updated = agentxFileMapper.update(null, wrapper);
        log.info("[agentx-console] 已 link 文件到会话: convId={}, files={}, updated={}",
                conversationId, fileIds.size(), updated);
    }

    /**
     * 按会话 id 查询所有已成功处理的文件（按创建时间升序）。
     * AgentService.buildInstructions 用它拼 system prompt 的"当前会话文件"段。
     */
    public List<AgentxFile> listByConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return agentxFileMapper.selectList(
                new LambdaQueryWrapper<AgentxFile>()
                        .eq(AgentxFile::getConversationId, conversationId)
                        .eq(AgentxFile::getStatus, "SUCCESS")
                        .orderByAsc(AgentxFile::getCreatedAt));
    }

    @Transactional(rollbackFor = Exception.class)
    public AgentxFile uploadFile(MultipartFile file) {
        String fileId = UUID.randomUUID().toString();
        String fileType = extractExt(file.getOriginalFilename());
        long fileSize = file.getSize();

        log.info("[agentx-console] 开始上传: fileId={}, name={}, type={}, size={}",
                fileId, file.getOriginalFilename(), fileType, fileSize);

        AgentxFile entity = new AgentxFile();
        entity.setFileId(fileId);
        entity.setFileName(file.getOriginalFilename());
        entity.setFileType(fileType);
        entity.setFileSize(fileSize);
        entity.setStatus("PROCESSING");
        entity.setEmbed(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        agentxFileMapper.insert(entity);

        try {
            // 上传 MinIO
            String objectName = generateObjectName(fileId, fileType);
            String minioPath = minioService.uploadFile(file, objectName);
            entity.setMinioPath(minioPath);

            entity.setStatus("SUCCESS");
            entity.setUpdatedAt(LocalDateTime.now());
            agentxFileMapper.updateById(entity);
            log.info("[agentx-console] 上传成功: fileId={}", fileId);
            return entity;

        } catch (Exception e) {
            log.error("[agentx-console] 上传失败: fileId={}", fileId, e);
            entity.setStatus("FAILED");
            entity.setUpdatedAt(LocalDateTime.now());
            agentxFileMapper.updateById(entity);
            throw new RuntimeException("文件上传失败: " + e.getMessage(), e);
        }
    }

    /** 本地副本路径（按用户隔离，fileName 取 basename 防路径穿越）。 */
    private Path localFilePath(String fileId, String fileName, long userId) {
        String safeName = fileName == null || fileName.isBlank() ? "file"
                : Path.of(fileName).getFileName().toString();
        return Path.of(workspaceDir, String.valueOf(userId), "uploads", fileId, safeName).normalize();
    }

    /**
     * 解析文件本地路径（供脚本/检测类 skill 直接读文件，按用户隔离）。
     * 本地副本存在则直接用；不存在时从 MinIO 下载重建（清理后主动下载）。
     * 本地与 MinIO 都没有才返回 null。
     */
    public String ensureLocalCopy(String fileId, String fileName, long userId) {
        Path local = localFilePath(fileId, fileName, userId);
        if (Files.isRegularFile(local)) {
            return local.toString();
        }
        AgentxFile entity = findByFileId(fileId);
        if (entity == null || StringUtils.isBlank(entity.getMinioPath())) {
            return null;
        }
        try {
            byte[] bytes = downloadFileBytes(fileId);
            Files.createDirectories(local.getParent());
            Files.write(local, bytes);
            log.info("[agentx-console] 从 MinIO 重建本地副本: userId={}, fileId={}", userId, fileId);
            return local.toString();
        } catch (Exception e) {
            log.warn("[agentx-console] 从 MinIO 重建本地副本失败: fileId={}, err={}", fileId, e.getMessage());
            return null;
        }
    }

    public AgentxFile getFileInfo(String fileId) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        return entity;
    }

    public String getFileContent(String fileId) {
        AgentxFile entity = getFileInfo(fileId);
        if (!"SUCCESS".equals(entity.getStatus())) {
            throw new IllegalStateException("文件尚未处理完成，状态: " + entity.getStatus());
        }
        String content = entity.getExtractedText();
        return StringUtils.isBlank(content) ? "该文件没有可识别的内容" : content;
    }

    /**
     * 从 MinIO 下载文件原始字节（analyzeFile 调多模态识别时用）。
     */
    public byte[] downloadFileBytes(String fileId) {
        AgentxFile entity = getFileInfo(fileId);
        if (StringUtils.isBlank(entity.getMinioPath())) {
            throw new IllegalStateException("文件未上传到对象存储: " + fileId);
        }
        String objectName = extractObjectName(entity.getMinioPath());
        try (InputStream is = minioService.downloadFile(objectName)) {
            return is.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("从 MinIO 下载文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把图片识别后的描述写回 extracted_text（懒缓存）。
     * 下次 analyzeFile 同一图片直接走 cache，不再调多模态。
     */
    public void saveExtractedText(String fileId, String text) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            return;
        }
        entity.setExtractedText(text);
        entity.setUpdatedAt(LocalDateTime.now());
        agentxFileMapper.updateById(entity);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteFile(String fileId) {
        AgentxFile entity = findByFileId(fileId);
        if (entity == null) {
            throw new IllegalArgumentException("文件不存在: " + fileId);
        }
        try {
            if (StringUtils.isNotBlank(entity.getMinioPath())) {
                String objectName = extractObjectName(entity.getMinioPath());
                minioService.deleteFile(objectName);
            }
        } catch (Exception e) {
            log.warn("[agentx-console] MinIO 删除失败（继续删 DB）: fileId={}, err={}", fileId, e.getMessage());
        }
        agentxFileMapper.delete(new QueryWrapper<AgentxFile>().eq("file_id", fileId));
        log.info("[agentx-console] 文件已删除: fileId={}", fileId);
    }

    private AgentxFile findByFileId(String fileId) {
        return agentxFileMapper.selectOne(
                new QueryWrapper<AgentxFile>().eq("file_id", fileId));
    }

    private boolean isLargeFile(String text) {
        return false;
    }

    public boolean isTextFile(String ext) {
        return !isImageFile(ext);
    }

    public boolean isImageFile(String ext) {
        return ext != null && IMAGE_EXTS.contains(ext.toLowerCase(Locale.ROOT));
    }

    private static String extractExt(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String generateObjectName(String fileId, String fileType) {
        return "file-" + fileId.replace("-", "") + "." + fileType;
    }

    private static String extractObjectName(String fullPath) {
        if (fullPath == null || !fullPath.contains("/")) {
            return fullPath;
        }
        return fullPath.substring(fullPath.lastIndexOf('/') + 1);
    }
}
