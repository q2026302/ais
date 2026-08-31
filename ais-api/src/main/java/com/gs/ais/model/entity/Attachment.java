package com.gs.ais.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "message_attachments")
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Avoid runtime HibernateProxy generation in GraalVM Native Image.
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "message_id")
    private Message message;

    /**
     * The user that uploaded this attachment. Uploads happen before a message is
     * associated ({@code message_id} is null at upload time), so ownership cannot
     * be derived solely from the message chain. Server-side integrations (e.g.
     * Feishu) have no authenticated user and leave this null; those attachments
     * are only reachable through the message/session chain.
     */
    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", length = 127)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * SHA-256 (lowercase hex) of the stored file content. Used to de-duplicate
     * uploads ("秒传") and reuse flows: several attachment records may reference
     * the same physical file when their content hashes match. Nullable so legacy
     * rows remain valid until the {@code AttachmentContentHashMigrationRunner}
     * backfills them.
     */
    @Column(name = "content_sha256", length = 64)
    private String contentSha256;

    @Column(name = "file_url", length = 512)
    private String fileUrl;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Attachment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Message getMessage() { return message; }
    public void setMessage(Message message) { this.message = message; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public String getFileUrl() { return fileUrl; }
    public void setFileUrl(String fileUrl) { this.fileUrl = fileUrl; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
