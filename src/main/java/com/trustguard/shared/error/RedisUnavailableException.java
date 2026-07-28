package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Redis is unreachable (Rule 9.5). Retryable — Redis outages are
 * typically transient, and a subsequent request may succeed once
 * connectivity is restored or the circuit breaker (Rule 9.6) closes.
 */
public final class RedisUnavailableException extends TrustGuardInfrastructureException {

    public RedisUnavailableException(String message) {
        super(message, ErrorCode.REDIS_UNAVAILABLE, true);
    }

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause, ErrorCode.REDIS_UNAVAILABLE, true);
    }
}