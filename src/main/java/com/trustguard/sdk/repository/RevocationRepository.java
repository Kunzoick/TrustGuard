package com.trustguard.sdk.repository;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/*
 * RLS-protected- same caveat as ApiKeyRepositiory. Not used by the pre-tenant-resolution auth path.
 */
public interface RevocationRepository extends JpaRepository<RevocationRecord, UUID>{
    boolean existsByTenantIdAndKeyId(UUID tenantId, String keyId);
}
