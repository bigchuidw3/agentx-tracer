package com.agentx.tracer.skill;

import com.agentx.tracer.domain.entities.AgentxSkill;
import com.agentx.tracer.mapper.AgentxSkillMapper;
import com.agentx.tracer.utils.FrontmatterUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Skill 内容存储（本地目录版，deploy.mode=standalone 或未配置时生效）。
 * <p>
 * 本地目录是权威源：上传复制到本地；定时扫描目录同步进 DB
 * （目录新增入库、目录删除清 DB 记录），用户手动放目录也能自动发现。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "deploy.mode", havingValue = "standalone", matchIfMissing = true)
public class LocalFsSkillContentStore implements SkillContentStore {

    private static final String SKILL_FILE = "SKILL.md";

    private final Path skillsDirectory;
    private final AgentxSkillMapper skillMapper;

    public LocalFsSkillContentStore(@Value("${skills.directory}") String skillsDirectory,
                                    AgentxSkillMapper skillMapper) {
        this.skillsDirectory = Path.of(skillsDirectory);
        this.skillMapper = skillMapper;
    }

    @Override
    public void save(String name, Path skillSourceDir) throws IOException {
        Path targetDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(targetDir)) {
            FileSystemUtils.deleteRecursively(targetDir);
        }
        FileSystemUtils.copyRecursively(skillSourceDir.toFile(), targetDir.toFile());
    }

    @Override
    public void delete(String name) throws IOException {
        Path skillDir = skillsDirectory.resolve(name);
        if (Files.isDirectory(skillDir)) {
            FileSystemUtils.deleteRecursively(skillDir);
        }
    }

    @Override
    public String storedPath(String name) {
        return skillsDirectory.resolve(name).toAbsolutePath().toString();
    }

    /**
     * 目录 → DB：FS 有 DB 没有 → 插入（enabled=1）；FS 没有 DB 有 → 删除；
     * 两边都有 → 从 FS 更新 description / skillPath（保留 enabled）。
     */
    @Override
    public void sync() {
        if (!Files.isDirectory(skillsDirectory)) {
            log.warn("[SkillManager] skills 目录不存在: {}", skillsDirectory.toAbsolutePath());
            return;
        }

        // 用户隔离后的两级结构：builtin/{name} 与 u-{userId}/{name}
        List<String> fsKeys = new ArrayList<>();
        try (DirectoryStream<Path> groupStream = Files.newDirectoryStream(skillsDirectory, Files::isDirectory)) {
            for (Path groupDir : groupStream) {
                String group = groupDir.getFileName().toString();
                if (!group.equals("builtin") && !group.matches("u-\\d+")) {
                    continue;
                }
                try (DirectoryStream<Path> skillStream = Files.newDirectoryStream(groupDir, Files::isDirectory)) {
                    for (Path dir : skillStream) {
                        if (Files.isRegularFile(dir.resolve(SKILL_FILE))) {
                            fsKeys.add(group + "/" + dir.getFileName().toString());
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[SkillManager] 扫描 skills 目录失败: {}", e.toString());
        }

        List<AgentxSkill> dbSkills = skillMapper.selectList(null);
        List<String> dbNames = dbSkills.stream().map(AgentxSkill::getName).toList();

        for (String key : fsKeys) {
            String absPath = skillsDirectory.resolve(key).toAbsolutePath().toString();
            String description = FrontmatterUtils.extractFromFile(
                    skillsDirectory.resolve(key).resolve(SKILL_FILE), "description");
            if (description == null) description = "";
            if (!dbNames.contains(key)) {
                AgentxSkill entity = new AgentxSkill();
                entity.setName(key);
                entity.setUserId(parseOwner(key));
                entity.setSkillPath(absPath);
                entity.setDescription(description);
                entity.setEnabled(1);
                entity.setFileName(null);
                skillMapper.insert(entity);
                log.info("[SkillManager] 存量 skill 入库: {} (enabled=1)", key);
            } else {
                AgentxSkill existing = dbSkills.stream()
                        .filter(s -> s.getName().equals(key)).findFirst().orElse(null);
                if (existing != null) {
                    boolean descChanged = !description.equals(existing.getDescription());
                    boolean pathChanged = !absPath.equals(existing.getSkillPath());
                    if (descChanged || pathChanged) {
                        LambdaUpdateWrapper<AgentxSkill> uw = new LambdaUpdateWrapper<AgentxSkill>()
                                .eq(AgentxSkill::getName, key);
                        if (descChanged) uw.set(AgentxSkill::getDescription, description);
                        if (pathChanged) uw.set(AgentxSkill::getSkillPath, absPath);
                        skillMapper.update(null, uw);
                    }
                }
            }
        }

        for (AgentxSkill dbSkill : dbSkills) {
            if (!fsKeys.contains(dbSkill.getName())) {
                skillMapper.delete(new LambdaQueryWrapper<AgentxSkill>()
                        .eq(AgentxSkill::getName, dbSkill.getName()));
                log.warn("[SkillManager] 孤儿 DB 记录已清理（目录不存在）: {}", dbSkill.getName());
            }
        }
    }

    /** 存储键 → 归属 userId（builtin=0，u-{id}=id） */
    private long parseOwner(String storageKey) {
        String group = storageKey.contains("/") ? storageKey.substring(0, storageKey.indexOf('/')) : "";
        if (group.equals("builtin")) return 0L;
        if (group.startsWith("u-")) {
            try { return Long.parseLong(group.substring(2)); } catch (NumberFormatException ignored) { }
        }
        return 0L;
    }
}
