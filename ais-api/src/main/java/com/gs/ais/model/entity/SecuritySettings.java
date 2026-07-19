package com.gs.ais.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * Singleton security policy used by login protection (captcha/IP lock).
 * Bootstrap defaults come from {@code app.security.*}; admins may override via API.
 */
@Entity
@Table(name = "security_settings")
@EntityListeners(AuditingEntityListener.class)
public class SecuritySettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "max_failures", nullable = false)
    private int maxFailures = 5;

    /** Sliding window that counts consecutive/recent failures, in minutes. */
    @Column(name = "failure_window_minutes", nullable = false)
    private int failureWindowMinutes = 15;

    /** How long an IP is rejected after exceeding maxFailures, in minutes. */
    @Column(name = "lock_duration_minutes", nullable = false)
    private int lockDurationMinutes = 30;

    @Column(name = "captcha_enabled", nullable = false)
    private boolean captchaEnabled = true;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getMaxFailures() {
        return maxFailures;
    }

    public void setMaxFailures(int maxFailures) {
        this.maxFailures = maxFailures;
    }

    public int getFailureWindowMinutes() {
        return failureWindowMinutes;
    }

    public void setFailureWindowMinutes(int failureWindowMinutes) {
        this.failureWindowMinutes = failureWindowMinutes;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }

    public boolean isCaptchaEnabled() {
        return captchaEnabled;
    }

    public void setCaptchaEnabled(boolean captchaEnabled) {
        this.captchaEnabled = captchaEnabled;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
