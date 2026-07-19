package com.gs.ais.model.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "system_model_settings")
@EntityListeners(AuditingEntityListener.class)
public class SystemModelSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "default_chat_model_id")
    private Long defaultChatModelId;

    @Column(name = "default_image_model_id")
    private Long defaultImageModelId;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDefaultChatModelId() { return defaultChatModelId; }
    public void setDefaultChatModelId(Long defaultChatModelId) { this.defaultChatModelId = defaultChatModelId; }
    public Long getDefaultImageModelId() { return defaultImageModelId; }
    public void setDefaultImageModelId(Long defaultImageModelId) { this.defaultImageModelId = defaultImageModelId; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
