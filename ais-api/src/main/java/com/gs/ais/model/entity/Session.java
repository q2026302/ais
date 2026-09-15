package com.gs.ais.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gs.ais.settings.SessionSettingsRegistry;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Map;

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

    @Column(name = "user_id")
    private Long userId;

    /** Whether this session may still receive its automatic LLM-generated title. */
    @JsonIgnore
    @Column(name = "auto_title_enabled", nullable = false)
    private boolean autoTitleEnabled;

    /**
     * 会话级设置的**稀疏 JSON**（按用途分组，例如 {@code {"draw":{"quality":"high"}}}）。
     *
     * <p>通用可空 TEXT 列：以后新增绘画参数或新增其它分组都不需要改表结构
     * （{@code ddl-auto: update} 自动建列，无需 migration）。只有显式设置过且与
     * 注册表默认值不同的键会被写入，读取时由 {@link SessionSettingsRegistry} 补齐
     * 默认值 —— 所以“没设置过”与“显式清空”在数据库里都是“这个键不存在”。
     *
     * <p>字段名与列名不同（列名固定为 {@code settings}），避免和下面回显生效值的
     * {@code settings} 属性重名。
     */
    @JsonIgnore
    @Column(name = "settings", columnDefinition = "TEXT")
    private String settingsJson;

    /**
     * Not a DB column — filled when listing sessions from the latest message.
     * Frontend auto-unread / previews depend on these fields.
     */
    @Transient
    private LocalDateTime lastMessageAt;

    /** Not a DB column — filled when listing sessions from the latest message. */
    @Transient
    private String lastMessagePreview;

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
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public String getLastMessagePreview() { return lastMessagePreview; }
    public void setLastMessagePreview(String lastMessagePreview) { this.lastMessagePreview = lastMessagePreview; }

    /**
     * 会话读接口回显的 {@code settings}：注册表默认值 ∪ 已存储的显式值。
     *
     * <p>不是数据库列，直接由 {@link #settingsJson} 派生；新增注册表参数会自动
     * 出现在这里，接口形状无需改动。未识别的键会被丢弃。
     */
    @JsonProperty("settings")
    public Map<String, Object> effectiveSettings() {
        return SessionSettingsRegistry.effective(settingsJson);
    }

    /** 持久化的稀疏 JSON 原文（可能为 {@code null}）。 */
    @JsonIgnore
    public String rawSettings() {
        return settingsJson;
    }

    @JsonIgnore
    public void setRawSettings(String settingsJson) {
        this.settingsJson = settingsJson;
    }
}
