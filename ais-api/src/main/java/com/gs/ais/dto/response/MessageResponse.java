package com.gs.ais.dto.response;

import com.gs.ais.model.entity.Message;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonSerialize;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "会话消息响应")
public class MessageResponse {

    @Schema(description = "消息 ID", example = "10")
    private Long id;

    @Schema(description = "消息角色：USER（用户）或 ASSISTANT（助手）", example = "USER")
    private MessageRole role;

    @Schema(description = "消息类型：CHAT、DRAW_REQUEST、DRAW_RESPONSE", example = "CHAT")
    private MessageType messageType;

    @Schema(description = "消息状态：PENDING、SUCCESS、FAILED", example = "SUCCESS")
    private MessageStatus status;

    @Schema(description = "消息内容（文本）", example = "一只可爱的橘猫坐在窗台上晒太阳")
    private String content;

    @Schema(description = "失败原因，状态为 FAILED 时返回")
    private String errorMessage;

    @Schema(description = "关联的图像 URL", example = "/api/images/test_1_20260711120000_a1b2c3d4.png")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String imageUrl;

    @Schema(description = "消息缩略图 URL（按消息 id 懒生成；前端渲染时动态附加 size 参数）", example = "/api/images/10/thumbnail")
    @JsonSerialize(using = SignedUrlSerializer.class)
    private String thumbnailUrl;

    @Schema(description = "绘画提示词")
    private String drawPrompt;

    @Schema(description = "绘画尺寸", example = "1024x1024")
    private String drawSize;

    @Schema(description = "绘画质量", example = "high")
    private String drawQuality;

    @Schema(description = "绘画格式", example = "png")
    private String drawFormat;

    @Schema(description = "绘画供应商 ID")
    private Long drawProviderId;

    @Schema(description = "对话供应商 ID（实际生成该助手消息时使用的模型）")
    private Long chatProviderId;

    @Schema(description = "关联的附件列表")
    private List<AttachmentResponse> attachments;

    @Schema(description = "Token 使用统计")
    private TokenUsage tokenUsage;

    @Schema(description = "父用户消息 ID；助手回复通过该字段关联到对应的用户消息")
    private Long parentMessageId;

    @Schema(description = "是否已编辑", example = "false")
    private boolean edited;

    @Schema(description = "是否已软删除，用于增量同步墓碑")
    private boolean deleted;

    @Schema(description = "消息创建时间")
    private LocalDateTime createdAt;

    /**
     * Advances when content/status is updated (PENDING → SUCCESS/FAILED).
     * Frontend auto-unread watermarks use coalesce(updatedAt, createdAt) so they
     * stay on the same timeline as session-list lastMessageAt.
     */
    @Schema(description = "消息最近活动时间（内容/状态更新时前进）")
    private LocalDateTime updatedAt;

    public static MessageResponse from(Message message) {
        MessageResponse resp = new MessageResponse();
        resp.setId(message.getId());
        resp.setRole(message.getRole());
        resp.setMessageType(message.getMessageType());
        resp.setStatus(message.getStatus());
        resp.setContent(message.getContent());
        resp.setErrorMessage(message.getErrorMessage());
        resp.setImageUrl(message.getImageUrl());
        if (message.getImageUrl() != null && !message.getImageUrl().isBlank()) {
            resp.setThumbnailUrl("/api/images/" + message.getId() + "/thumbnail");
        }
        resp.setDrawPrompt(message.getDrawPrompt());
        resp.setDrawSize(message.getDrawSize());
        resp.setDrawQuality(message.getDrawQuality());
        resp.setDrawFormat(message.getDrawFormat());
        resp.setDrawProviderId(message.getDrawProviderId());
        resp.setChatProviderId(message.getChatProviderId());
        if (message.getAttachments() != null) {
            resp.setAttachments(message.getAttachments().stream()
                    .map(AttachmentResponse::from)
                    .toList());
        }
        if (message.getPromptTokens() != null || message.getCompletionTokens() != null || message.getTotalTokens() != null) {
            resp.setTokenUsage(new TokenUsage(
                    message.getPromptTokens(),
                    message.getCompletionTokens(),
                    message.getTotalTokens()));
        }
        resp.setParentMessageId(message.getParentMessageId());
        resp.setEdited(message.isEdited());
        resp.setDeleted(message.isDeleted());
        resp.setCreatedAt(message.getCreatedAt());
        resp.setUpdatedAt(message.getUpdatedAt());
        return resp;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public MessageRole getRole() { return role; }
    public void setRole(MessageRole role) { this.role = role; }
    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }
    public MessageStatus getStatus() { return status; }
    public void setStatus(MessageStatus status) { this.status = status; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
    public Long getDrawProviderId() { return drawProviderId; }
    public void setDrawProviderId(Long drawProviderId) { this.drawProviderId = drawProviderId; }
    public Long getChatProviderId() { return chatProviderId; }
    public void setChatProviderId(Long chatProviderId) { this.chatProviderId = chatProviderId; }
    public List<AttachmentResponse> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentResponse> attachments) { this.attachments = attachments; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }
    public Long getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(Long parentMessageId) { this.parentMessageId = parentMessageId; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
