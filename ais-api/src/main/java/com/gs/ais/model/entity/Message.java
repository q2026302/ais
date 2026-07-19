package com.gs.ais.model.entity;

import com.gs.ais.model.enums.MessageRole;
import com.gs.ais.model.enums.MessageStatus;
import com.gs.ais.model.enums.MessageType;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Native Image does not support generating HibernateProxy classes at runtime.
    // This association is read together with messages by the session APIs, so eager
    // loading also avoids a proxy while keeping the response mapping native-safe.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private MessageRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", length = 32)
    private MessageType messageType = MessageType.CHAT;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16)
    private MessageStatus status = MessageStatus.SUCCESS;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @Column(name = "draw_prompt", columnDefinition = "TEXT")
    private String drawPrompt;

    @Column(name = "draw_size", length = 32)
    private String drawSize;

    @Column(name = "draw_quality", length = 32)
    private String drawQuality;

    @Column(name = "draw_format", length = 32)
    private String drawFormat;

    @Column(name = "draw_provider_id")
    private Long drawProviderId;

    @OneToMany(mappedBy = "message", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Attachment> attachments = new ArrayList<>();

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(name = "parent_message_id")
    private Long parentMessageId;

    @Column(name = "is_edited")
    private boolean edited;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (messageType == null) {
            messageType = MessageType.CHAT;
        }
        if (status == null) {
            status = MessageStatus.SUCCESS;
        }
    }

    public Message() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Session getSession() { return session; }
    public void setSession(Session session) { this.session = session; }
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
    public List<Attachment> getAttachments() { return attachments; }
    public void setAttachments(List<Attachment> attachments) { this.attachments = attachments; }
    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }
    public Long getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(Long parentMessageId) { this.parentMessageId = parentMessageId; }
    public boolean isEdited() { return edited; }
    public void setEdited(boolean edited) { this.edited = edited; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
