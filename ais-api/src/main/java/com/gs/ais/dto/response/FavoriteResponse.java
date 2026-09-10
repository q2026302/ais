package com.gs.ais.dto.response;

import com.gs.ais.model.entity.Favorite;
import com.gs.ais.util.ReferenceFileUrls;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of a saved work. Mirrors the wire shape of {@link MessageResponse}
 * for the image fields so the two ends can render them with the same helpers.
 */
@Schema(description = "收藏作品响应")
public class FavoriteResponse {

    @Schema(description = "收藏 ID", example = "5")
    private Long id;

    @Schema(description = "收藏归属用户 ID（管理员据此逐条管理；普通用户只会看到自己的记录）", example = "2")
    private Long userId;

    @Schema(description = "收藏归属用户名（管理员作品库用于分辨记录归属）", example = "alice")
    private String userName;

    @Schema(description = "来源消息 ID", example = "42")
    private Long messageId;

    @Schema(description = "来源会话 ID（原会话存在时用于跳转）")
    private Long sessionId;

    @Schema(description = "来源会话是否仍然存在")
    private boolean sessionAvailable;

    @Schema(description = "作品原图 URL", example = "/api/images/generated/abc.png")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String imageUrl;

    @Schema(description = "作品缩略图 URL（按收藏 id 懒生成；前端渲染时动态附加 size 参数）",
            example = "/api/favorites/5/thumbnail")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String thumbnailUrl;

    @Schema(description = "生成该作品的提示词")
    private String drawPrompt;

    @Schema(description = "生成尺寸", example = "1024x1024")
    private String drawSize;

    @Schema(description = "生成质量", example = "high")
    private String drawQuality;

    @Schema(description = "生成格式", example = "png")
    private String drawFormat;

    @Schema(description = "生成时使用的参考图片")
    private List<FavoriteReferenceResponse> referenceImages = new ArrayList<>();

    @Schema(description = "收藏时间")
    private LocalDateTime createdAt;

    /**
     * Maps a persisted snapshot to its response. {@code sessionAvailable} is
     * resolved by the caller (the service) because it requires a session lookup;
     * {@code userName} likewise requires a user lookup and may be {@code null}.
     */
    public static FavoriteResponse from(Favorite favorite, boolean sessionAvailable, String userName) {
        FavoriteResponse resp = new FavoriteResponse();
        resp.setId(favorite.getId());
        resp.setUserId(favorite.getUserId());
        resp.setUserName(userName);
        resp.setMessageId(favorite.getMessageId());
        resp.setSessionId(favorite.getSessionId());
        resp.setSessionAvailable(sessionAvailable);
        resp.setImageUrl(favorite.getImageUrl());
        if (favorite.getImageUrl() != null && !favorite.getImageUrl().isBlank()) {
            resp.setThumbnailUrl("/api/favorites/" + favorite.getId() + "/thumbnail");
        }
        resp.setDrawPrompt(favorite.getDrawPrompt());
        resp.setDrawSize(favorite.getDrawSize());
        resp.setDrawQuality(favorite.getDrawQuality());
        resp.setDrawFormat(favorite.getDrawFormat());
        List<String> references = ReferenceFileUrls.split(favorite.getReferenceFileUrls());
        List<FavoriteReferenceResponse> referenceResponses = new ArrayList<>(references.size());
        for (int index = 0; index < references.size(); index++) {
            referenceResponses.add(new FavoriteReferenceResponse(
                    references.get(index),
                    "/api/favorites/" + favorite.getId() + "/references/" + index + "/thumbnail"));
        }
        resp.setReferenceImages(referenceResponses);
        resp.setCreatedAt(favorite.getCreatedAt());
        return resp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public boolean isSessionAvailable() { return sessionAvailable; }
    public void setSessionAvailable(boolean sessionAvailable) { this.sessionAvailable = sessionAvailable; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public String getDrawPrompt() { return drawPrompt; }
    public void setDrawPrompt(String drawPrompt) { this.drawPrompt = drawPrompt; }
    public String getDrawSize() { return drawSize; }
    public void setDrawSize(String drawSize) { this.drawSize = drawSize; }
    public String getDrawQuality() { return drawQuality; }
    public void setDrawQuality(String drawQuality) { this.drawQuality = drawQuality; }
    public String getDrawFormat() { return drawFormat; }
    public void setDrawFormat(String drawFormat) { this.drawFormat = drawFormat; }
    public List<FavoriteReferenceResponse> getReferenceImages() { return referenceImages; }
    public void setReferenceImages(List<FavoriteReferenceResponse> referenceImages) { this.referenceImages = referenceImages; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
