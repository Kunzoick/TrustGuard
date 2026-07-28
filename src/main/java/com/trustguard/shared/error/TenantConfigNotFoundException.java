package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * No tenant configuration exists for the given tenantId. Distinct from
 * Rule 9.5's cache-unavailable fallback (which applies most-restrictive
 * defaults) — this signals the tenant has no configuration record at
 * all. Not retryable.
 */
public final class TenantConfigNotFoundException extends TrustGuardException {

    public TenantConfigNotFoundException(String message) {
        super(message, ErrorCode.TENANT_CONFIG_NOT_FOUND, false);
    }

    public TenantConfigNotFoundException(String message, Throwable cause) {
        super(message, cause, ErrorCode.TENANT_CONFIG_NOT_FOUND, false);
    }
}