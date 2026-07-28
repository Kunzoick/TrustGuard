package com.trustguard.shared.error;

import com.trustguard.shared.enums.ErrorCode;

/**
 * Tenant configuration requests policy_type = boolean_rules, which is
 * deliberately unsupported in V1 (Rule 7.8 — deferred to V2). Not
 * retryable; the policy type does not change between requests.
 */
public final class PolicyTypeNotSupportedException extends TrustGuardException {

    public PolicyTypeNotSupportedException(String message) {
        super(message, ErrorCode.POLICY_TYPE_NOT_SUPPORTED_IN_V1, false);
    }

    public PolicyTypeNotSupportedException(String message, Throwable cause) {
        super(message, cause, ErrorCode.POLICY_TYPE_NOT_SUPPORTED_IN_V1, false);
    }
}