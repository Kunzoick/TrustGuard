package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * An ANONYMOUS actor event arrived without sessionId, and the tenant's
 * allowWeakAnonymousId config is false (Rule 6.3). Not retryable without
 * the client supplying the missing field.
 */
public final class SessionIdRequiredException extends TrustGuardException {

    public SessionIdRequiredException(String message) {
        super(message, ErrorCode.SESSION_ID_REQUIRED, false);
    }

    public SessionIdRequiredException(String message, Throwable cause) {
        super(message, cause, ErrorCode.SESSION_ID_REQUIRED, false);
    }
}