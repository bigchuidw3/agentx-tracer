package com.agentx.tracer.skill;

import com.agentx.tracer.domain.entities.AgentxSkill;
import com.agentx.tracer.domain.entities.SkillInfo;
import com.agentx.tracer.mapper.AgentxSkillMapper;
import com.agentx.tracer.utils.FileUtils;
import com.agentx.tracer.utils.FrontmatterUtils;
import com.agentx.tracer.utils.ZipUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skills 管理器 — 上传校验、启停、删除、启用目录查询（内容存储委托给 SkillContentStore）。
 * <p>
 * <b>用户隔离（ToC 形态）</b>：技能分两级——
 * <ul>
 *   <li>平台内置：user_id=0，目录 {skills.root}/builtin/{name}，所有人可见，只读</li>
 *   <li>用户私有：user_id={userId}，目录 {skills.root}/u-{userId}/{name}，仅本人可管理</li>
 * </ul>
 * 表 name 列存 <b>存储键</b>（storageKey = "builtin/{name}" 或 "u-{userId}/{name}"，全局唯一），
 * 对 ContentStore 与集群同步天然透明；对外展示用纯技能名（存储键末段）。
 * <p>
 * deploy.mode=standalone：本地目录是权威源；cluster：DB + MinIO 是权威源，各节点同步到本地。
 */
@Slf4j
@Component
public class SkillManager {

    private static final String SKILL_FILE = "SKILL.md";
    public static final long BUILTIN_USER_ID = 0L;

    private final Path skillsDirectory;
    private final JdbcTemplate jdbcTemplate;
    private final AgentxSkillMapper skillMapper;
    private final SkillContentStore contentStore;

    public SkillManager(@Value("${skills.directory}") String skillsDirectory,
                        DataSource dataSource,
                        AgentxSkillMapper skillMapper,
                        SkillContentStore contentStore) {
        this.skillsDirectory = Path.of(skillsDirectory);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.skillMapper = skillMapper;
        this.contentStore = contentStore;
    }

    @PostConstruct
    void init() {
        ensureTableExists();
        contentStore.sync();
    }

    /**
     * 定时同步（每 3 分钟）：localFs 实现为目录入库，minio 实现为按表从 MinIO 拉取。
     */
    @Scheduled(fixedDelay = 180000, initialDelay = 180000)
    void scheduledSync() {
        contentStore.sync();
    }

    /** 存储键：内置技能 */
    public static String builtinKey(String name) {
        return "builtin/" + name;
    }

    /** 存储键：用户私有技能 */
    public static String userKey(long userId, String name) {
        return "u-" + userId + "/" + name;
    }

    /** 存储键 → 纯技能名 */
    public static String displayName(String storageKey) {
        if (storageKey == null) return "";
        int idx = storageKey.lastIndexOf('/');
        return idx >= 0 ? storageKey.substring(idx + 1) : storageKey;
    }

    /**
     * 自动建表 + 存量升级（user_id 列）。
     */
    private void ensureTableExists() {
        String createSql = """
                CREATE TABLE agentx_skill (
                    id           BIGINT       NOT NULL,
                    name         VARCHAR(500) NOT NULL COMMENT '存储键: builtin/{name} 或 u-{userId}/{name}',
                    user_id      BIGINT       NOT NULL DEFAULT 0 COMMENT '归属: 0=平台内置, >0=用户私有',
                    skill_path   VARCHAR(3000) DEFAULT NULL,
                    description  VARCHAR(3000) DEFAULT '',
                    enabled      TINYINT      NOT NULL DEFAULT 1,
                    file_name    VARCHAR(255) DEFAULT NULL,
                    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
                    updated_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    PRIMARY KEY (id)
                )
                """;
        try {
            jdbcTemplate.execute(createSql + " DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
        } catch (Exception e) {
            try {
                jdbcTemplate.execute(createSql);
            } catch (Exception e2) {
                log.debug("[SkillManager] 建表跳过（可能已存在）: {}", e2.getMessage());
            }
        }
        try {
            jdbcTemplate.execute("CREATE UNIQUE INDEX uk_agentx_skill_name ON agentx_skill (name)");
        } catch (Exception e) {
            log.debug("[SkillManager] 唯一索引跳过（可能已存在）: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE agentx_skill ADD COLUMN skill_path VARCHAR(500) DEFAULT NULL");
        } catch (Exception e) {
            log.debug("[SkillManager] skill_path 列跳过: {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE agentx_skill ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0 "
                    + "COMMENT '归属: 0=平台内置, >0=用户私有'");
            log.info("[SkillManager] 存量表已补 user_id 列");
        } catch (Exception e) {
            log.debug("[SkillManager] user_id 列跳过（可能已存在）: {}", e.getMessage());
        }
        log.info("[SkillManager] agentx_skill 表就绪");
    }

    /**
     * 列出当前用户可见的技能：平台内置（user_id=0）+ 本人私有，供前端分组展示。
     */
    public List<SkillInfo> list(long userId) {
        List<AgentxSkill> skills = skillMapper.selectList(
                new LambdaQueryWrapper<AgentxSkill>()
                        .in(AgentxSkill::getUserId, List.of(BUILTIN_USER_ID, userId))
                        .orderByAsc(AgentxSkill::getUserId, AgentxSkill::getName));
        List<SkillInfo> result = new ArrayList<>();
        for (AgentxSkill s : skills) {
            result.add(new SkillInfo(displayName(s.getName()), s.getName(),
                    s.getSkillPath() == null ? "" : s.getSkillPath(),
                    s.getDescription() == null ? "" : s.getDescription(),
                    s.getEnabled() != null && s.getEnabled() == 1,
                    s.getUserId() == null ? BUILTIN_USER_ID : s.getUserId()));
        }
        return result;
    }

    /**
     * 当前用户可用的启用技能目录（builtin + 本人）：供 AgentService 构建 SkillsTool。
     */
    public List<String> getEnabledSkillDirs(long userId) {
        List<AgentxSkill> enabled = skillMapper.selectList(
                new LambdaQueryWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getEnabled, 1)
                        .in(AgentxSkill::getUserId, List.of(BUILTIN_USER_ID, userId)));
        List<String> dirs = new ArrayList<>();
        for (AgentxSkill s : enabled) {
            // name 即存储键（含用户目录前缀），resolve 天然多级
            String path = skillsDirectory.resolve(s.getName()).toAbsolutePath().toString();
            if (Files.isDirectory(Path.of(path))) {
                dirs.add(path);
            } else {
                log.warn("[SkillManager] enabled skill 目录不存在: {} → {}", s.getName(), path);
            }
        }
        return dirs;
    }

    /**
     * 上传 zip 压缩包到当前用户的私有空间，自动解压。
     * 新 skill 默认启用，覆盖上传保留原 enabled 状态。
     */
    public SkillInfo uploadZip(MultipartFile file, long userId) throws IOException {
        String originalName = file.getOriginalFilename();
        if (originalName == null || !originalName.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("仅支持 .zip 文件");
        }

        // 1. 解压到临时目录
        Files.createDirectories(skillsDirectory);
        Path tempDir = Files.createTempDirectory(skillsDirectory, "skill-upload-");
        try {
            ZipUtils.extract(file.getInputStream(), tempDir);

            // 2. 查找 SKILL.md
            Path skillMd = FileUtils.findFile(tempDir, SKILL_FILE, 3);
            if (skillMd == null) {
                throw new IllegalArgumentException("压缩包中未找到 SKILL.md 文件");
            }

            // 3. 解析 name / description
            Path skillSourceDir = skillMd.getParent();
            String content = Files.readString(skillMd);
            String name = FrontmatterUtils.extract(content, "name");
            if (name == null || name.isBlank()) {
                name = skillSourceDir.getFileName().toString();
            }
            name = name.trim();
            validateSkillName(name);

            String description = FrontmatterUtils.extract(content, "description");
            if (description == null) description = "";

            // 4. 存储内容（存储键 = u-{userId}/{name}）
            String storageKey = userKey(userId, name);
            contentStore.save(storageKey, skillSourceDir);

            // 5. upsert DB
            String storedPath = contentStore.storedPath(storageKey);
            AgentxSkill existing = skillMapper.selectOne(
                    new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, storageKey));
            boolean enabled;
            if (existing == null) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(storageKey);
                entity.setUserId(userId);
                entity.setSkillPath(storedPath);
                entity.setDescription(description.trim());
                entity.setEnabled(1);
                entity.setFileName(originalName);
                skillMapper.insert(entity);
                enabled = true;
                log.info("[SkillManager] 新 skill 上传: {} (user={}, enabled=1)", storageKey, userId);
            } else {
                skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, storageKey)
                        .set(AgentxSkill::getSkillPath, storedPath)
                        .set(AgentxSkill::getDescription, description.trim())
                        .set(AgentxSkill::getFileName, originalName));
                enabled = existing.getEnabled() != null && existing.getEnabled() == 1;
                log.info("[SkillManager] skill 覆盖上传: {} (enabled={})", storageKey, enabled);
            }

            // 6. 通知其他节点立即同步
            contentStore.notifyChanged();

            return new SkillInfo(name, storageKey, storedPath, description.trim(), enabled, userId);
        } finally {
            FileUtils.deleteRecursively(tempDir);
        }
    }

    /**
     * 切换 skill 启用状态（仅本人技能；内置只读）。
     */
    public boolean toggleEnabled(String storageKey, boolean enabled, long userId) {
        AgentxSkill existing = requireOwned(storageKey, userId);
        int newValue = enabled ? 1 : 0;
        if (existing.getEnabled() != null && existing.getEnabled() == newValue) {
            return enabled;
        }
        skillMapper.update(null, new LambdaUpdateWrapper<AgentxSkill>()
                .eq(AgentxSkill::getName, storageKey)
                .set(AgentxSkill::getEnabled, newValue));
        log.info("[SkillManager] skill 切换: {} → enabled={}", storageKey, enabled);
        return enabled;
    }

    /**
     * 删除 skill（内容 + DB 记录；仅本人技能，内置只读）。
     */
    public boolean delete(String storageKey, long userId) throws IOException {
        validateStorageKey(storageKey);
        requireOwned(storageKey, userId);
        contentStore.delete(storageKey);
        int deleted = skillMapper.delete(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, storageKey));
        if (deleted > 0) {
            contentStore.notifyChanged();
        }
        log.info("[SkillManager] skill 删除: {} (db={})", storageKey, deleted > 0);
        return deleted > 0;
    }

    /**
     * 校验归属：记录存在、属于当前用户（内置拒绝写操作）。
     */
    private AgentxSkill requireOwned(String storageKey, long userId) {
        AgentxSkill existing = skillMapper.selectOne(
                new LambdaQueryWrapper<AgentxSkill>().eq(AgentxSkill::getName, storageKey));
        if (existing == null) {
            throw new IllegalArgumentException("skill 不存在: " + displayName(storageKey));
        }
        long owner = existing.getUserId() == null ? BUILTIN_USER_ID : existing.getUserId();
        if (owner == BUILTIN_USER_ID) {
            throw new IllegalArgumentException("平台内置技能不可修改或删除");
        }
        if (owner != userId) {
            throw new IllegalArgumentException("无权操作他人技能");
        }
        return existing;
    }

    /**
     * 校验技能名（防路径穿越，作用于纯技能名段）。
     */
    private void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill 名称不能为空");
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            throw new IllegalArgumentException("非法 skill 名称: " + name);
        }
    }

    /**
     * 校验存储键（仅允许 builtin/ 或 u-{id}/ 前缀，段内字符安全）。
     */
    private void validateStorageKey(String key) {
        if (key == null || !key.matches("(builtin|u-\\d+)/[\\w.-]+")) {
            throw new IllegalArgumentException("非法 skill 标识");
        }
    }
}
