package com.gs.ais.dto.response;

import com.gs.ais.model.entity.Message;
import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;

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
    private String imageUrl;

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

    @Schema(description = "关联的附件列表")
    private List<AttachmentResponse> attachments;

    @Schema(description = "Token 使用统计")
    private TokenUsage tokenUsage;

    @Schema(description = "父用户消息 ID；助手回复通过该字段关联到对应的用户消息")
    private Long parentMessageId;

    @Schema(description = "是否已编辑", example = "false")
    private boolean edited;

    @Schema(description = "消息创建时间")
    private LocalDateTime createdAt;

    public static MessageResponse from(Message message) {
        MessageResponse resp = new MessageResponse();
        resp.setId(message.getId());
        resp.setRole(message.getRole());
        resp.setMessageType(message.getMessageType());
        resp.setStatus(message.getStatus());
        resp.setContent(message.getContent());
        resp.setErrorMessage(message.getErrorMessage());
        resp.setImageUrl(message.getImageUrl());
        resp.setDrawPrompt(message.getDrawPrompt());
        resp.setDrawSize(message.getDrawSize());
        resp.setDrawQuality(message.getDrawQuality());
        resp.setDrawFormat(message.getDrawFormat());
        resp.setDrawProviderId(message.getDrawProviderId());
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
        resp.setCreatedAt(message.getCreatedAt());
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
    public List<AttachmentResponse> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentResponse> attachments) { this.attachments = attachments; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }
    public Long getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(Long parentMessageId) { this.parentMessageId = parentMessageId; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
