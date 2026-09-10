package com.gs.ais.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A user's saved work (作品库 / 收藏).
 *
 * <p>The row is a <strong>snapshot</strong> of the favourited generated image and
 * the context used to create it. It deliberately keeps its own copies of the
 * image path, draw options and reference-image paths instead of joining the
 * original message, so a favourite stays complete after the message and/or its
 * session are deleted. Storage stays single-copy: only the database reference is
 * duplicated, never the physical bytes.
 *
 * <p>All stored URLs are raw (signature-less) context-relative paths, exactly like
 * {@link Message#getImageUrl()} / {@link Message#getReferenceFileUrls()}. Signatures
 * are added at response serialization time by
 * {@code SignedUrlSerializer}.
 *
 * <p>Uniqueness is per {@code (user_id, message_id)}: every user owns an independent
 * snapshot, so two users favouriting the same message never collide and a save by one
 * user can never be mistaken for another user's save.
 *
 * <p>The unique constraint is deliberately <strong>not</strong> declared here. Hibernate's
 * community {@code SQLiteDialect} drops {@code @UniqueConstraint} DDL silently (its
 * {@code getAlterTableToAddUniqueKeyCommand} returns {@code ""}), so the annotation would
 * give a real index on H2/MySQL but none at all on SQLite — the production database.
 * Declaring it would also make startup fail on an installation that already holds
 * duplicate rows, because Hibernate runs schema update before any backfill can. The
 * single source of truth is the {@code uk_favorites_user_message} unique index created
 * (and legacy duplicates collapsed) by
 * {@link com.gs.ais.config.FavoriteUniquenessMigrationRunner}, which behaves identically
 * on SQLite, MySQL and H2.
 */
@Entity
@Table(name = "favorites")
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owner of the favourite (the user who saved the work). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * Source message the snapshot was taken from. Unique per owner: each user may
     * save a message at most once, but different users each keep their own copy.
     */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** Snapshot of the originating session id, used to jump back when it still exists. */
    @Column(name = "session_id")
    private Long sessionId;

    /** Raw generated-image path (no signature), same format as {@code messages.image_url}. */
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

    /**
     * Snapshot of the reference images used for the generation, stored as
     * newline-separated raw paths ({@code /api/images/...} / {@code /api/attachments/...})
     * exactly like {@link Message#getReferenceFileUrls()}: user-uploaded attachments
     * and server-side reused files are both included.
     */
    @Column(name = "reference_file_urls", columnDefinition = "TEXT")
    private String referenceFileUrls;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Favorite() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
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
    public String getReferenceFileUrls() { return referenceFileUrls; }
    public void setReferenceFileUrls(String referenceFileUrls) { this.referenceFileUrls = referenceFileUrls; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
