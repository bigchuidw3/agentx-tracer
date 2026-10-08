package com.agentx.tracer.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Markdown frontmatter（YAML 首段 --- 之间）解析工具。
 * <p>
 * 不引入完整 YAML 解析依赖，按行扫描提取 {@code key: value}。
 */
public final class FrontmatterUtils {

    private static final String DELIMITER = "---";

    private FrontmatterUtils() {
    }

    /**
     * 直接从文件读取并提取 frontmatter 值。
     *
     * @param file Markdown 文件路径
     * @param key  frontmatter 中的 key
     * @return value 值，文件不存在或 key 不存在返回 null
     */
    public static String extractFromFile(Path file, String key) {
        if (!Files.isRegularFile(file)) return null;
        try {
            return extract(Files.readString(file), key);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从 Markdown frontmatter 提取指定 key 的 value。
     *
     * @param content Markdown 全文
     * @param key     frontmatter 中的 key（如 "name"、"description"）
     * @return value 值，找不到返回 null
     */
    public static String extract(String content, String key) {
        String[] lines = content.split("\\r?\\n");
        boolean inFrontmatter = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (DELIMITER.equals(trimmed)) {
                if (inFrontmatter) break;
                inFrontmatter = true;
                continue;
            }
            if (inFrontmatter && trimmed.startsWith(key + ":")) {
                return trimmed.substring((key + ":").length()).trim();
            }
        }
        return null;
    }
}
