package com.agentx.tracer.skill;

import com.agentx.tracer.domain.entities.AgentxSkill;
import com.agentx.tracer.mapper.AgentxSkillMapper;
import com.agentx.tracer.service.MinioService;
import com.agentx.tracer.utils.ZipUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Skill 内容存储（MinIO 版，deploy.mode=cluster 时生效）。
 * <p>
 * DB + MinIO 是权威源：上传时 skill 打包成 zip 存 MinIO（objectKey = skills/{name}.zip）
 * 并落本地（本节点立即生效）；上传/删除后广播 pub/sub，其他节点收到立即同步拉取，
 * 每 3 分钟定时任务兜底。不存在单机版"各节点以本地目录为权威互相删除"的问题。
 * <p>
 * 本地版本记录在 skills 目录下的 .skill-sync.json（name → 上次同步的 updated_at），
 * 版本一致跳过拉取，避免频繁删重建正在使用的目录。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deploy.mode", havingValue = "cluster")
public class MinioSkillContentStore implements SkillContentStore {

    private static final String OBJECT_PREFIX = "skills/";
    private static final String SYNC_STATE_FILE = ".skill-sync.json";

    private final Path skillsDirectory;
    private final AgentxSkillMapper skillMapper;
    private final MinioService minioService;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * 同节点 sync 防并发：定时与广播可能同时触发，串行化避免同时删重建同一目录
     */
    private final ReentrantLock syncLock = new ReentrantLock();

    public MinioSkillContentStore(@Value("${skills.directory}") String skillsDirectory,
                                  AgentxSkillMapper skillMapper,
                                  MinioService minioService,
                                  StringRedisTemplate redis) {
        this.skillsDirectory = Path.of(skillsDirectory);
        this.skillMapper = skillMapper;
        this.minioService = minioService;
        this.redis = redis;
    }

    private String objectKey(String name) {
        return OBJECT_PREFIX + name + ".zip";
    }

    public static String channel() {
        return "agentx:skill:changed";
    }

    /**
     * 上传：目录打包 zip → MinIO + 复制一份到本地（本节点立即可用）。
     */
    @Override
    public void save(String name, Path skillSourceDir) throws IOException {
        if (!pushToMinio(name, skillSourceDir)) {
            throw new IOException("skill 上传 MinIO 失败: " + name);
        }
        Path targetDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(targetDir)) {
            FileSystemUtils.deleteRecursively(targetDir);
        }
        FileSystemUtils.copyRecursively(skillSourceDir.toFile(), targetDir.toFile());
        log.info("[SkillManager] skill 已上传 MinIO 并落本地: {}", name);
    }

    @Override
    public void delete(String name) throws IOException {
        try {
            minioService.deleteFile(objectKey(name));
        } catch (Exception e) {
            log.warn("[SkillManager] MinIO 删除失败（继续删本地与 DB）: {} err={}", name, e.getMessage());
        }
        Path skillDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(skillDir)) {
            FileSystemUtils.deleteRecursively(skillDir);
        }
    }

    @Override
    public String storedPath(String name) {
        return objectKey(name);
    }

    /**
     * 变更广播：各节点收到后立即触发 sync，不必等定时周期。
     */
    @Override
    public void notifyChanged() {
        redis.convertAndSend(channel(), "changed");
    }

    /**
     * DB → 本地：按表对比本地版本，从 MinIO 拉取新增/变更，删除表里没有的本地目录。
     */
    @Override
    public void sync() {
        if (!syncLock.tryLock()) {
            return;
        }
        try {
            doSync();
        } finally {
            syncLock.unlock();
        }
    }

    private void doSync() {
        try {
            Files.createDirectories(skillsDirectory);
        } catch (IOException e) {
            log.warn("[SkillManager] skills 目录创建失败: {}", e.toString());
            return;
        }
        Map<String, String> localVersions = readSyncState();
        Map<String, String> nextVersions = new HashMap<>();
        Set<String> dbNames = new HashSet<>();

        List<AgentxSkill> dbSkills = skillMapper.selectList(null);
        for (AgentxSkill skill : dbSkills) {
            dbNames.add(skill.getName());
            // skill_path 统一为 MinIO objectKey（单机迁移的老数据存的是本地绝对路径，顺手纠正）
            if (!objectKey(skill.getName()).equals(skill.getSkillPath())) {
                skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, skill.getName())
                        .set(AgentxSkill::getSkillPath, objectKey(skill.getName())));
            }
            String version = skill.getUpdatedAt() == null ? "" : skill.getUpdatedAt().toString();
            if (version.equals(localVersions.get(skill.getName()))) {
                nextVersions.put(skill.getName(), version);   // 本地已是该版本，跳过
                continue;
            }
            if (pullFromMinio(skill.getName(), version)) {
                nextVersions.put(skill.getName(), version);   // 拉取成功才记版本，失败的下周期重试
            } else if (Files.isDirectory(skillsDirectory.resolve(skill.getName()))
                    && pushToMinio(skill.getName(), skillsDirectory.resolve(skill.getName()))) {
                // 单机模式迁移场景：老数据本地有目录但 MinIO 没有副本，以本地为准补传，三处对齐
                nextVersions.put(skill.getName(), version);
                log.info("[SkillManager] MinIO 缺失，已用本地目录补传: {}", skill.getName());
            }
        }

        // DB 没有而本地有 → 删本地（DB 权威）；跳过上传/同步进行中的临时目录，防止并发误删
        int removed = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDirectory, Files::isDirectory)) {
            for (Path dir : stream) {
                String name = dir.getFileName().toString();
                if (name.startsWith("skill-upload-") || name.startsWith("skill-sync-")) {
                    continue;
                }
                if (!dbNames.contains(name)) {
                    FileSystemUtils.deleteRecursively(dir);
                    nextVersions.remove(name);
                    removed++;
                    log.info("[SkillManager] 本地目录已按 DB 清理: {}", name);
                }
            }
        } catch (IOException e) {
            log.warn("[SkillManager] 本地目录清理扫描失败: {}", e.toString());
        }

        writeSyncState(nextVersions);
        log.debug("[SkillManager] skill 同步完成: db={}, removedLocal={}", dbNames.size(), removed);
    }

    /**
     * 打包本地目录上传 MinIO（上传、迁移补传共用）。
     */
    private boolean pushToMinio(String name, Path sourceDir) {
        try {
            byte[] zip = zip(sourceDir);
            minioService.uploadStream(new ByteArrayInputStream(zip), zip.length,
                    objectKey(name), "application/zip");
            return true;
        } catch (Exception e) {
            log.warn("[SkillManager] MinIO 上传失败: {} err={}", name, e.getMessage());
            return false;
        }
    }

    /**
     * 从 MinIO 拉 zip 解压覆盖本地（先解压到临时目录再替换，避免半成品）。
     * 返回是否成功：成功才记版本，失败的留在下个周期重试。
     */
    private boolean pullFromMinio(String name, String version) {
        Path targetDir = skillsDirectory.resolve(name);
        try (InputStream in = minioService.downloadFile(objectKey(name))) {
            Path tempDir = Files.createTempDirectory(skillsDirectory, "skill-sync-");
            try {
                ZipUtils.extract(in, tempDir);
                if (Files.isDirectory(targetDir)) {
                    FileSystemUtils.deleteRecursively(targetDir);
                }
                FileSystemUtils.copyRecursively(tempDir.toFile(), targetDir.toFile());
            } finally {
                FileSystemUtils.deleteRecursively(tempDir);
            }
            log.info("[SkillManager] skill 同步拉取: {} version={}", name, version);
            return true;
        } catch (Exception e) {
            log.warn("[SkillManager] skill 拉取失败（下周期重试）: {} err={}", name, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readSyncState() {
        try {
            Path state = skillsDirectory.resolve(SYNC_STATE_FILE);
            if (Files.isRegularFile(state)) {
                return mapper.readValue(state.toFile(), Map.class);
            }
        } catch (Exception e) {
            log.warn("[SkillManager] 同步状态文件读取失败，按全量处理: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    private void writeSyncState(Map<String, String> state) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(skillsDirectory.resolve(SYNC_STATE_FILE).toFile(), state);
        } catch (Exception e) {
            log.warn("[SkillManager] 同步状态文件写入失败: {}", e.getMessage());
        }
    }

    private byte[] zip(Path sourceDir) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipUtils.compress(sourceDir, out);
        return out.toByteArray();
    }
}
