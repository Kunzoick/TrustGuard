package com.trustguard.api.admin.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for admin_users (platform table, no RLS). Mutation is confined to the domain
 * methods below so the lockout and token_version rules live in one place.
 */
@Entity
@Table(name = "admin_users")
public class AdminUser {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false, updatable = false, length = 255)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "failed_attempt_count", nullable = false)
    private int failedAttemptCount;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "failed_window_started_at")
    private Instant failedWindowStartedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. */
    protected AdminUser() {
    }

    /**
     * Records a failed attempt inside the caller's row lock.
     *
     * @param now         current instant
     * @param maxAttempts failures that trigger a lock
     * @param window      failure window length
     * @return true if this failure locked the account
     */
    public boolean registerFailure(Instant now, int maxAttempts, Duration window) {
        if (lockedAt != null) {
            return false;
        }
        if (failedWindowStartedAt == null || !now.isBefore(failedWindowStartedAt.plus(window))) {
            failedWindowStartedAt = now;
            failedAttemptCount = 1;
        } else {
            failedAttemptCount++;
        }
        boolean locksNow = failedAttemptCount >= maxAttempts;
        if (locksNow) {
            lockedAt = now;
        }
        updatedAt = now;
        return locksNow;
    }

    /**
     * Resets the failure counter after a successful login.
     *
     * @param now current instant
     */
    public void registerSuccess(Instant now) {
        failedAttemptCount = 0;
        failedWindowStartedAt = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    /**
     * Invalidates every token issued to this admin.
     *
     * @param now current instant
     */
    public void incrementTokenVersion(Instant now) {
        tokenVersion++;
        updatedAt = now;
    }

    /**
     * Returns whether the account is locked until manual unlock.
     *
     * @return true when locked
     */
    public boolean isLocked() {
        return lockedAt != null;
    }

    /**
     * Returns the admin id.
     *
     * @return admin id
     */
    public UUID getId() {
        return id;
    }

    /**
     * Returns the username.
     *
     * @return username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Returns the bcrypt hash.
     *
     * @return password hash
     */
    public String getPasswordHash() {
        return passwordHash;
    }

    /**
     * Returns the current token version.
     *
     * @return token version
     */
    public int getTokenVersion() {
        return tokenVersion;
    }
}