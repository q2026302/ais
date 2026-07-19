package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "文件上传响应")
public class UploadResponse {

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

    public UploadResponse() {}

    public UploadResponse(Long id, String originalName, String contentType, Long fileSize, String fileUrl) {
        this.id = id;
        this.originalName = originalName;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.fileUrl = fileUrl;
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
}
