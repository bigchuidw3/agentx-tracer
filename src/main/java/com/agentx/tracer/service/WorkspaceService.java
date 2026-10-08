package com.agentx.tracer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 工作区文件目录服务：按当前用户列出 / 下载其工作区（{@code workspace/<userId>/}）下的文件。
 *
 * <p>所有相对路径都限定在用户工作区根内，防止目录穿越。
 *
 * @author agentx-console
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceService {

    @Value("${app.workspace-dir:./workspace}")
    private String workspaceDir;

    /**
     * 列出目录内容。
     *
     * @param userId       当前用户 ID
     * @param relativePath 相对用户工作区根的路径（空串或 "/" 表示根）
     * @return { path, parent, entries: [{ name, path, isDir, size, modifiedAt }] }
     */
    public Map<String, Object> list(long userId, String relativePath) {
        Path root = userRoot(userId);
        Path dir = resolve(root, relativePath);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在");
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString()))
                    .forEach(p -> entries.add(entryOf(root, p)));
        } catch (IOException e) {
            throw new IllegalStateException("读取目录失败: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", relativePath == null || relativePath.isBlank() ? "" : relativePath);
        result.put("parent", parentOf(relativePath));
        result.put("entries", entries);
        return result;
    }

    /**
     * 下载文件字节。
     */
    public DownloadedFile download(long userId, String relativePath) {
        Path root = userRoot(userId);
        Path file = resolve(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在");
        }
        try {
            return new DownloadedFile(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage());
        }
    }

    public record DownloadedFile(String fileName, byte[] bytes) {
    }

    /**
     * 预览文件（仅文本/代码/图片，返回 content-type + 字节）。
     */
    public PreviewedFile preview(long userId, String relativePath) {
        Path root = userRoot(userId);
        Path file = resolve(root, relativePath);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在");
        }
        String contentType = contentTypeOf(extensionOf(file.getFileName().toString()));
        if (contentType == null) {
            throw new IllegalArgumentException("该文件类型不支持预览，请下载查看");
        }
        try {
            if (Files.size(file) > PREVIEW_MAX_BYTES) {
                throw new IllegalArgumentException("文件过大（超过 2MB），请下载查看");
            }
            return new PreviewedFile(file.getFileName().toString(), contentType, Files.readAllBytes(file));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 删除文件或目录（递归）。
     */
    public void delete(long userId, String relativePath) {
        Path root = userRoot(userId);
        Path target = resolve(root, relativePath);
        if (target.equals(root)) {
            throw new IllegalArgumentException("不能删除根目录");
        }
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("文件不存在");
        }
        deleteRecursively(target);
    }

    /**
     * 按文件名关键词递归搜索。
     */
    public List<Map<String, Object>> search(long userId, String keyword) {
        Path root = userRoot(userId);
        if (keyword == null || keyword.isBlank() || !Files.exists(root)) {
            return List.of();
        }
        String kw = keyword.toLowerCase();
        List<Map<String, Object>> results = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> !p.equals(root))
                    .filter(p -> p.getFileName().toString().toLowerCase().contains(kw))
                    .forEach(p -> results.add(entryOf(root, p)));
        } catch (IOException e) {
            throw new IllegalStateException("搜索失败: " + e.getMessage());
        }
        return results;
    }

    /**
     * 清空用户工作区（删除根下所有条目，保留根目录）。
     */
    public void clean(long userId) {
        Path root = userRoot(userId);
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.list(root)) {
            stream.forEach(this::deleteRecursively);
        } catch (IOException e) {
            throw new IllegalStateException("清理失败: " + e.getMessage());
        }
    }

    /**
     * 批量删除（跳过不存在/根目录）。
     */
    public void deleteBatch(long userId, List<String> paths) {
        Path root = userRoot(userId);
        for (String p : paths) {
            Path target = resolve(root, p);
            if (target.equals(root) || !Files.exists(target)) {
                continue;
            }
            deleteRecursively(target);
        }
    }

    /**
     * 批量下载（多个文件打包为 zip）。
     */
    public DownloadedFile downloadZip(long userId, List<String> paths) {
        Path root = userRoot(userId);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (String p : paths) {
                Path file = resolve(root, p);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                zos.putNextEntry(new ZipEntry(p.replace('\\', '/')));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("打包失败: " + e.getMessage());
        }
        return new DownloadedFile("workspace.zip", baos.toByteArray());
    }

    public record PreviewedFile(String fileName, String contentType, byte[] bytes) {
    }

    private Map<String, Object> entryOf(Path root, Path p) {
        Map<String, Object> e = new LinkedHashMap<>();
        boolean isDir = Files.isDirectory(p);
        e.put("name", p.getFileName().toString());
        e.put("path", root.relativize(p).toString().replace('\\', '/'));
        e.put("isDir", isDir);
        try {
            BasicFileAttributes attr = Files.readAttributes(p, BasicFileAttributes.class);
            e.put("size", isDir ? null : attr.size());
            e.put("modifiedAt", attr.lastModifiedTime().toInstant().toString());
        } catch (IOException ex) {
            e.put("size", null);
            e.put("modifiedAt", null);
        }
        return e;
    }

    /**
     * 解析相对路径到用户工作区根内，防目录穿越。
     */
    private Path resolve(Path root, String relativePath) {
        String rel = relativePath == null || relativePath.isBlank() || "/".equals(relativePath)
                ? "" : relativePath;
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法路径");
        }
        return target;
    }

    private Path userRoot(long userId) {
        return Path.of(workspaceDir, String.valueOf(userId)).normalize().toAbsolutePath();
    }

    private String parentOf(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || "/".equals(relativePath)) {
            return null;
        }
        String norm = relativePath.replace('\\', '/');
        int idx = norm.lastIndexOf('/');
        return idx <= 0 ? "" : norm.substring(0, idx);
    }

    private static final long PREVIEW_MAX_BYTES = 2 * 1024 * 1024;

    private static final Map<String, String> IMAGE_CONTENT_TYPES = Map.of(
            "png", "image/png", "jpg", "image/jpeg", "jpeg", "image/jpeg",
            "gif", "image/gif", "webp", "image/webp", "bmp", "image/bmp", "svg", "image/svg+xml");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "java", "py", "js", "ts", "json", "xml", "yml", "yaml",
            "sh", "sql", "html", "css", "log", "properties", "csv", "go", "c", "cpp", "h",
            "rb", "php", "conf", "ini", "bat", "ps1", "jsx", "tsx", "vue");

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private String contentTypeOf(String ext) {
        String image = IMAGE_CONTENT_TYPES.get(ext);
        if (image != null) {
            return image;
        }
        return TEXT_EXTENSIONS.contains(ext) ? "text/plain; charset=UTF-8" : null;
    }

    private void deleteRecursively(Path target) {
        try {
            if (Files.isDirectory(target)) {
                try (var stream = Files.walk(target)) {
                    stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            } else {
                Files.delete(target);
            }
        } catch (IOException e) {
            throw new IllegalStateException("删除失败: " + e.getMessage());
        }
    }
}
