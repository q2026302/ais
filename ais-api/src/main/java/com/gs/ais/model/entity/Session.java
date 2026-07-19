package com.gs.ais.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "sessions")
@EntityListeners(AuditingEntityListener.class)
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "title", length = 255)
    private String title;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "chat_provider_id")
    private Long chatProviderId;

    @Column(name = "image_provider_id")
    private Long imageProviderId;

    @Column(name = "external_channel", length = 32)
    private String externalChannel;

    @Column(name = "external_chat_id", length = 255)
    private String externalChatId;

    /** Whether this session may still receive its automatic LLM-generated title. */
    @JsonIgnore
    @Column(name = "auto_title_enabled", nullable = false)
    private boolean autoTitleEnabled;

    public Session() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Long getChatProviderId() { return chatProviderId; }
    public void setChatProviderId(Long chatProviderId) { this.chatProviderId = chatProviderId; }
    public Long getImageProviderId() { return imageProviderId; }
    public void setImageProviderId(Long imageProviderId) { this.imageProviderId = imageProviderId; }
    public String getExternalChannel() { return externalChannel; }
    public void setExternalChannel(String externalChannel) { this.externalChannel = externalChannel; }
    public String getExternalChatId() { return externalChatId; }
    public void setExternalChatId(String externalChatId) { this.externalChatId = externalChatId; }
    public boolean isAutoTitleEnabled() { return autoTitleEnabled; }
    public void setAutoTitleEnabled(boolean autoTitleEnabled) { this.autoTitleEnabled = autoTitleEnabled; }
}
