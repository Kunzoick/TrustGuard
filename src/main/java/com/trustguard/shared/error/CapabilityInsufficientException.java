package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * API key capability set does not cover the endpoint being called
 * (Rule 5.4). Not retryable — the key's capabilities do not change
 * between identical requests.
 */
public final class CapabilityInsufficientException extends TrustGuardException {

    public CapabilityInsufficientException(String message) {
        super(message, ErrorCode.CAPABILITY_INSUFFICIENT, false);
    }

    public CapabilityInsufficientException(String message, Throwable cause) {
        super(message, cause, ErrorCode.CAPABILITY_INSUFFICIENT, false);
    }
}