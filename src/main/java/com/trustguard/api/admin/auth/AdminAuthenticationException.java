package com.trustguard.api.admin.auth;
import java.util.Map;

import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.TrustGuardException;

/**
 * Admin authentication failure-> carries its HTTP status and render the Map.of()
 * error body shape.
 */
public class AdminAuthenticationException extends TrustGuardException{
    private final int httpStatus;
    /**
     * creates the exception
     *
     * @param code registered ErrorCode
     * @param httpStatus HTTP status to return
     * @param message client-safe message
     */
    public AdminAuthenticationException(ErrorCode code,  int httpStatus, String message){
       super(message, code, false);
       this.httpStatus= httpStatus;
    }
    /**
     * Returns the HTTP status for this failure
     *
     * @return HTTP status code
     */
    public int httpStatus(){
        return httpStatus;
    }
    /**
     * builds the response body for this failure
     *
     * @return error body map
     */
    public Map<String, Object> toResponseBody(){
        return Map.<String, Object>of("error", Map.<String, Object>of("code",
                code().name(), "message", getMessage(), "retryable", false));
    }

}
