package com.agentx.tracer.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件系统操作工具。
 */
public final class FileUtils {

    private FileUtils() {
    }

    /**
     * 递归删除目录及其所有内容。
     *
     * @param dir 要删除的目录
     * @throws IOException 删除失败
     */
    public static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    /**
     * 在目录树中查找指定文件名的文件（深度优先，返回第一个匹配）。
     *
     * @param root     搜索根目录
     * @param fileName 目标文件名（大小写不敏感）
     * @param maxDepth 最大搜索深度
     * @return 第一个匹配的路径，找不到返回 null
     */
    public static Path findFile(Path root, String fileName, int maxDepth) throws IOException {
        try (Stream<Path> walk = Files.walk(root, maxDepth)) {
            Optional<Path> found = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst();
            return found.orElse(null);
        }
    }
}
