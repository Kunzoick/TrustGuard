package com.trustguard.api.admin.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

/**
 * Admin user access. Deliberately extends JpaRepository directly, not BaseRepository: admin_users
 * is a platform table with no tenant_id (Rule 4.1 does not apply).
 * <p>
 * There is intentionally no entity-returning find-by-username method (RULING 23): the login read must
 * go through the projection so it cannot leave a managed entity behind.
 */
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    /**
     * Reads the login columns for a username without loading a managed entity.
     *
     * @param username login name
     * @return the login view, if present
     */
    Optional<AdminLoginView> findLoginViewByUsername(String username);

    /**
     * Loads an admin with a pessimistic write lock (SELECT ... FOR UPDATE). Must run inside a transaction.
     *
     * @param id admin id
     * @return the locked admin, if present
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AdminUser a where a.id = :id")
    Optional<AdminUser> findByIdForUpdate(@Param("id") UUID id);
}