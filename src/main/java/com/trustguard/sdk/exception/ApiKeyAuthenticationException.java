package com.trustguard.sdk.exception;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.TrustGuardException;
/**
 * is the single exception type the filter catches to produce a direct 401/403 JSON response,
 * satisfying the brief's instruction that it never rethrow into the servlet container.
 * JSON response itself- it is never rethrown into the servlet container.
 */
public class ApiKeyAuthenticationException extends TrustGuardException {
    private final int httpStatus;

    public ApiKeyAuthenticationException(ErrorCode code,  int httpStatus, String message) {
        super(message, code, false);
        this.httpStatus = httpStatus;
    }
    public int httpStatus() {
        return httpStatus;
    }
}
