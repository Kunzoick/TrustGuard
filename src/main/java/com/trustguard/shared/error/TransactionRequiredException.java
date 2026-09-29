package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Thrown when TenantRlsAspect fires but no active Spring-managed transaction exists on the current thread.
 * This is the safety net against a misssing @Transactional boundary
 * silently proceeding here would mean SET LOCAL never fires and RLS silently degrades
 * to whatever default curent_setting resolves to
 */

public class TransactionRequiredException extends TrustGuardInfrastructureException {
    public TransactionRequiredException(String message) {
        super(message, ErrorCode.TRANSACTION_REQUIRED, false);
    }
}
