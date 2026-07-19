package com.gs.ais.dto.response;

import com.gs.ais.model.entity.Attachment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "附件信息响应")
public class AttachmentResponse {

    @Schema(description = "附件 ID", example = "1")
    private Long id;

    @Schema(description = "文件原始名称", example = "cat.jpg")
    private String originalName;

    @Schema(description = "文件 MIME 类型", example = "image/jpeg")
    private String contentType;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long fileSize;

    @Schema(description = "文件访问 URL", example = "/api/attachments/cat.jpg")
    private String fileUrl;

    @Schema(description = "上传时间")
    private LocalDateTime createdAt;

    public static AttachmentResponse from(Attachment attachment) {
        AttachmentResponse resp = new AttachmentResponse();
        resp.setId(attachment.getId());
        resp.setOriginalName(attachment.getOriginalName());
        resp.setContentType(attachment.getContentType());
        resp.setFileSize(attachment.getFileSize());
        resp.setFileUrl(attachment.getFileUrl());
        resp.setCreatedAt(attachment.getCreatedAt());
        return resp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
