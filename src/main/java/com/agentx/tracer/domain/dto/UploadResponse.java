package com.agentx.tracer.domain.vo;

import com.agentx.tracer.domain.entities.AgentxFile;

/**
 * 文件上传响应。
 *
 * @param fileId   文件唯一 ID
 * @param fileName 文件名
 * @param fileType 文件类型（扩展名）
 * @param fileSize 文件大小（字节）
 * @param status   状态（SUCCESS / FAILED 等）
 * @param embed    是否已向量化（1=是，0=否）
 */
public record UploadResponse(String fileId,
                             String fileName,
                             String fileType,
                             long fileSize,
                             String status,
                             int embed) {

    public static UploadResponse from(AgentxFile f) {
        return new UploadResponse(
                f.getFileId(),
                f.getFileName(),
                f.getFileType(),
                f.getFileSize() == null ? 0 : f.getFileSize(),
                f.getStatus(),
                f.getEmbed() == null ? 0 : f.getEmbed());
    }
}
