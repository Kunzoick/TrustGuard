package com.trustguard.tenant.context;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import java.util.Set;

/**
 * Rule 4.1 Layer 1 — resolved exclusively from authenticated
 * credentials (Rule 4.3), never from request headers, query
 * parameters, or body. Immutable. capabilities is defensively
 * copied to an unmodifiable set at construction.
 */
public record TenantContext(
        TenantId tenantId,
        ProjectId projectId,
        String keyId,
        Environment environment,
        Set<Capability> capabilities,
        Integer configVersion
) {
    public TenantContext{
        capabilities= Set.copyOf(capabilities);
    }
}
