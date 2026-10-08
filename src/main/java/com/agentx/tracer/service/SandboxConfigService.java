package com.agentx.tracer.service;

import com.agentx.ai.core.sandbox.ContainerNameUtil;
import com.agentx.ai.core.sandbox.WorkspaceSpec;
import com.agentx.ai.core.sandbox.docker.DockerBackend;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱执行开关（按用户隔离）。
 *
 * <p>复用 sys_config KV 表，key 为 {@code sandbox.enabled.<userId>}。
 * 默认开启（未配置时视为 true）；关闭后该用户的工具调用退化为宿主执行。
 *
 * @author agentx-console
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SandboxConfigService {

    private final JdbcTemplate jdbcTemplate;

    /** 宿主机 workspace 绝对路径（沙箱容器 bind mount 用） */
    @Value("${app.workspace-host-dir:}")
    private String workspaceHostDir;

    /** 宿主机 skills 绝对路径（沙箱容器 bind mount 用） */
    @Value("${app.skills-host-dir:}")
    private String skillsHostDir;

    /** 应用容器内 workspace 路径（沙箱容器内保持同一路径） */
    @Value("${app.workspace-dir:./workspace}")
    private String workspaceDir;

    /** 应用容器内 skills 路径（沙箱容器内保持同一路径） */
    @Value("${skills.directory}")
    private String skillsRootDir;

    /** 沙箱容器镜像 */
    @Value("${app.sandbox-image:ubuntu:22.04}")
    private String sandboxImage;

    /** 沙箱容器网络：true=开通（bridge），false=禁用 */
    @Value("${app.sandbox-network-enabled:true}")
    private boolean sandboxNetworkEnabled;

    public Map<String, Object> get(long userId) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", isEnabled(userId));
        return config;
    }

    public void save(long userId, boolean enabled) {
        upsert(userId, String.valueOf(enabled));
        if (enabled) {
            // 开启沙箱：立即拉起常驻容器，省去首次对话时现建容器的等待
            ensureSandbox(userId);
        } else {
            // 关闭沙箱：销毁该用户的常驻容器（workspace 已挂载宿主机目录，文件不丢）
            destroySandbox(userId);
        }
    }

    /** 构建与 AgentServiceImpl 一致的 DockerBackend（含宿主机 workspace + skills 挂载）。 */
    private DockerBackend buildBackend(long userId) {
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

    /** 拉起该用户的常驻沙箱容器（已存在则跳过）。 */
    private void ensureSandbox(long userId) {
        try {
            String scopeKey = String.valueOf(userId);
            String containerName = ContainerNameUtil.toContainerName(scopeKey);
            DockerBackend backend = buildBackend(userId);
            if (backend.findExisting(containerName) != null) {
                log.info("[agentx-console] 沙箱容器已存在，跳过: {}", containerName);
                return;
            }
            // 清理可能的 exited 残留容器（如宿主机重启后），避免 create 时 name conflict
            backend.destroyByName(containerName);
            backend.createSandbox(WorkspaceSpec.empty(), containerName);
            log.info("[agentx-console] 沙箱容器已拉起: {}", containerName);
        } catch (Exception e) {
            log.warn("[agentx-console] 拉起沙箱容器失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 销毁该用户的常驻沙箱容器（autoRelease=false 时容器不随会话销毁，
     * 用户关闭沙箱开关时在此显式销毁）。
     */
    private void destroySandbox(long userId) {
        try {
            String containerName = ContainerNameUtil.toContainerName(String.valueOf(userId));
            DockerBackend.builder().build().destroyByName(containerName);
            log.info("[agentx-console] 已销毁沙箱容器: {}", containerName);
        } catch (Exception e) {
            log.warn("[agentx-console] 销毁沙箱容器失败: userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 是否启用沙箱（默认 true）。
     */
    public boolean isEnabled(long userId) {
        String value = read(userId);
        return value == null || "true".equalsIgnoreCase(value);
    }

    private String key(long userId) {
        return "sandbox.enabled." + userId;
    }

    private String read(long userId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT cfg_value FROM sys_config WHERE cfg_key = ?", String.class, key(userId));
        } catch (Exception e) {
            return null;
        }
    }

    private void upsert(long userId, String value) {
        String cfgKey = key(userId);
        try {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_config WHERE cfg_key = ?", Integer.class, cfgKey);
            if (exists != null && exists > 0) {
                jdbcTemplate.update(
                        "UPDATE sys_config SET cfg_value = ? WHERE cfg_key = ?", value, cfgKey);
            } else {
                jdbcTemplate.update(
                        "INSERT INTO sys_config (cfg_key, cfg_value) VALUES (?, ?)", cfgKey, value);
            }
        } catch (Exception e) {
            log.warn("[agentx-console] 保存沙箱配置失败: key={}, err={}", cfgKey, e.getMessage());
        }
    }
}
