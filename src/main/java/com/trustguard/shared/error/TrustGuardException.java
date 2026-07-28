package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Base class for business-rule violations- maps to a 4xx response at the api layer (rule 17.3)
 * Every instance carries the errorCode and a retryable flag so GlobalExceptionHandler can build the rule
 * 16.6 error response schema without each call site re-deciding those two facts independently
 */
public abstract class TrustGuardException extends RuntimeException {
    private final ErrorCode code;
    private final boolean retryable;

    protected TrustGuardException(String message, ErrorCode code, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    protected TrustGuardException(String message, Throwable cause, ErrorCode code, boolean retryable) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
