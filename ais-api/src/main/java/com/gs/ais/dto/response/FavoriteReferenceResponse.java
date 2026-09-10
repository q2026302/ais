package com.gs.ais.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

@Schema(description = "收藏作品中的一张参考图片")
public class FavoriteReferenceResponse {

    @Schema(description = "参考图片原始 URL", example = "/api/attachments/cat.png")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String fileUrl;

    @Schema(description = "参考图片缩略图 URL（按收藏 id + 序号懒生成）", example = "/api/favorites/5/references/0/thumbnail")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String thumbnailUrl;

    public FavoriteReferenceResponse() {
    }

    public FavoriteReferenceResponse(String fileUrl, String thumbnailUrl) {
        this.fileUrl = fileUrl;
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
}
