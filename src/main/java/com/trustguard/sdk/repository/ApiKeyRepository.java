package com.trustguard.sdk.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * RLS-protected- runs on the main trustguard_app connection pool
 * Not used by the pre-tenant-resolution auth path; those queries would return empty against this connection
 * since no TenantContext exists yet to populate app.tenant_id. This interface exists for future authenticated
 * operations where TenantContext is already set.
 */
public interface ApiKeyRepository extends JpaRepository<ApiKeyRecord, UUID>{
    Optional<ApiKeyRecord> findByKeyId(String keyId);
}
