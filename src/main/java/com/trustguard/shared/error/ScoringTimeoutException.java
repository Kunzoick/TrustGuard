package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Scoring computation exceeded the tenant's computationTimeoutMs. Not
 * retryable — Rule 9.7 explicitly excludes scoring computation from
 * server-side retry; retrying adds latency in the user's own request
 * path and the same load conditions are likely still present. In normal
 * operation this condition is caught internally to build a DEGRADED
 * response (Rule 9.1) rather than propagated as an HTTP error.
 */
public final class ScoringTimeoutException extends TrustGuardInfrastructureException {

    public ScoringTimeoutException(String message) {
        super(message, ErrorCode.SCORING_TIMEOUT, false);
    }

    public ScoringTimeoutException(String message, Throwable cause) {
        super(message, cause, ErrorCode.SCORING_TIMEOUT, false);
    }
}