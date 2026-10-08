package com.agentx.tracer.domain.entities;

/**
 * Skill 展示信息
 *
 * @param name        纯技能名（展示用）
 * @param storageKey  存储键（builtin/{name} 或 u-{userId}/{name}，操作接口用）
 * @param skillPath   存储位置标识（本地路径 / MinIO objectKey）
 * @param description 描述
 * @param enabled     是否启用
 * @param ownerUserId 归属用户（0=平台内置）
 */
public record SkillInfo(String name, String storageKey, String skillPath,
                        String description, boolean enabled, long ownerUserId) {
}
