package com.trustguard.api.admin.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Closed interface projection used by the login read (RULING 23). Selecting only these columns means no
 * managed AdminUser enters the persistence context, so the later PESSIMISTIC_WRITE query cannot return
 * a stale cached copy when open-in-view is on.
 */
public interface AdminLoginView {

    /**
     * Returns the admin id.
     *
     * @return admin id
     */
    UUID getId();

    /**
     * Returns the username.
     *
     * @return username
     */
    String getUsername();

    /**
     * Returns the bcrypt hash.
     *
     * @return password hash
     */
    String getPasswordHash();

    /**
     * Returns when the account was locked, or null when it is not locked.
     *
     * @return lock timestamp or null
     */
    Instant getLockedAt();
}