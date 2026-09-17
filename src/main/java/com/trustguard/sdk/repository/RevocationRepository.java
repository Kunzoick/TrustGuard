package com.trustguard.sdk.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

/*
 * RLS-protected- same caveat as ApiKeyRepositiory. Not used by the pre-tenant-resolution auth path.
 */
public interface RevocationRepository extends JpaRepository<RevocationRecord, UUID>{
    boolean existsByTenantIdAndKeyId(UUID tenantId, String keyId);
}
