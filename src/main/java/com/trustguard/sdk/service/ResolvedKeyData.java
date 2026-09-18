package com.trustguard.sdk.service;

import com.trustguard.shared.enums.Capability;

import java.util.Set;
import java.util.UUID;

/**
 * Data carrier returned by KeyLookupService.resolve().
 * TenantContext construction is deliberately NOT done in
 * KeyLookupService — Rule 4.3 confines TenantContext
 * construction to the auth filter boundary.
 * ApiKeyAuthFilter constructs TenantContext from this record.
 */
public record ResolvedKeyData(
        String keyId,
        UUID tenantId,
        UUID projectId,
        String environment,
        Set<Capability> capabilities
) {}