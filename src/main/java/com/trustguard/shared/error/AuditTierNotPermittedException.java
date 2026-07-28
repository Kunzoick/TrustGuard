package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * A regulated tenant category (fintech, healthcare, government)
 * attempted to select the AGGREGATE audit tier, which Rule 10.5
 * prohibits for those categories. Not retryable without changing the
 * requested tier.
 */
public final class AuditTierNotPermittedException extends TrustGuardException {

    public AuditTierNotPermittedException(String message) {
        super(message, ErrorCode.AUDIT_TIER_NOT_PERMITTED, false);
    }

    public AuditTierNotPermittedException(String message, Throwable cause) {
        super(message, cause, ErrorCode.AUDIT_TIER_NOT_PERMITTED, false);
    }
}