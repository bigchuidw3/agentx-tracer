package com.agentx.tracer.domain.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单轮问答详情（历史会话回放用）。
 *
 * @param id          记录 id（agentx_session 主键）
 * @param question    用户问题
 * @param answer      最终回答
 * @param timeline    前端时间线渲染数据 JSON
 * @param createdAt   创建时间
 * @param attachments 该轮用户上传的文件列表（按 session_id 反查），可能为空
 */
public record SessionMessageVO(Long id,
                               String question,
                               String answer,
                               String timeline,
                               LocalDateTime createdAt,
                               List<AttachmentInfo> attachments) {

    /**
     * 文件附件元数据（不含 extracted_text，避免历史列表膨胀）。
     */
    public record AttachmentInfo(String fileId,
                                 String fileName,
                                 String fileType,
                                 Long fileSize) {
    }
}
