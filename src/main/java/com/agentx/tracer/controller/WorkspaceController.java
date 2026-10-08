package com.agentx.tracer.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.agentx.tracer.auth.service.AuthService;
import com.agentx.tracer.common.R;
import com.agentx.tracer.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 工作区文件目录接口（按当前登录用户隔离）。
 */
@RestController
@RequestMapping("/api/workspace")
@RequiredArgsConstructor
@SaCheckLogin
public class WorkspaceController {

    private final WorkspaceService workspaceService;
    private final AuthService authService;

    @GetMapping("/list")
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "") String path) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        return R.ok(workspaceService.list(userId, path));
    }

    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String path) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            WorkspaceService.DownloadedFile f = workspaceService.download(userId, path);
            String encoded = URLEncoder.encode(f.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(f.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/preview")
    public ResponseEntity<byte[]> preview(@RequestParam String path) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            WorkspaceService.PreviewedFile f = workspaceService.preview(userId, path);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(f.contentType()))
                    .body(f.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(415).build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    @DeleteMapping
    public R<Void> delete(@RequestParam String path) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        workspaceService.delete(userId, path);
        return R.ok();
    }

    @GetMapping("/search")
    public R<List<Map<String, Object>>> search(@RequestParam String keyword) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        return R.ok(workspaceService.search(userId, keyword));
    }

    @DeleteMapping("/clean")
    public R<Void> clean() {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        workspaceService.clean(userId);
        return R.ok();
    }

    @DeleteMapping("/batch")
    public R<Void> deleteBatch(@RequestParam List<String> paths) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.fail("未登录");
        }
        workspaceService.deleteBatch(userId, paths);
        return R.ok();
    }

    @GetMapping("/download-zip")
    public ResponseEntity<byte[]> downloadZip(@RequestParam List<String> paths) {
        Long userId = authService.getCurrentUserIdOrNull();
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            WorkspaceService.DownloadedFile f = workspaceService.downloadZip(userId, paths);
            String encoded = URLEncoder.encode(f.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(f.bytes());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
