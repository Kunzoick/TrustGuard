package com.trustguard.shared.error;

import com.trustguard.shared.enums.ErrorCode;

/**
 * API key's declared environment does not match the request's
 * environment (Rule 5.3). Not retryable.
 */
public final class EnvironmentMismatchException extends TrustGuardException {

    public EnvironmentMismatchException(String message) {
        super(message, ErrorCode.ENVIRONMENT_MISMATCH, false);
    }

    public EnvironmentMismatchException(String message, Throwable cause) {
        super(message, cause, ErrorCode.ENVIRONMENT_MISMATCH, false);
    }
}