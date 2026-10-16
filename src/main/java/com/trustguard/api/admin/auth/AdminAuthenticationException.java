package com.trustguard.api.admin.auth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.TrustGuardException;

/**
 * Admin authentication failure-> carries its HTTP status and render the Map.of()
 * error body shape. 401, 400 & 404 responses of the admin Api. the optional details list is rendered only when non-empty;
 * entries never contain a submitted value.
 */
public class AdminAuthenticationException extends TrustGuardException{
    private static final String KEY_ERROR= "error";
    private static final String KEY_CODE= "code";
    private static final String KEY_MESSAGE= "message";
    private static final String KEY_RETRYABLE= "retryable";
    private static final String KEY_DETAILS= "details";

    private final int httpStatus;
    private final List<Map<String, String>> details;
    /**
     * creates the exception
     *
     * @param code registered ErrorCode
     * @param httpStatus HTTP status to return
     * @param message client-safe message
     */
    public AdminAuthenticationException(ErrorCode code,  int httpStatus, String message){
        this(code, httpStatus, message, List.of());
    }
    /**
     * Creates the exception with field-level details.
     *
     * @param code       registered ErrorCode
     * @param httpStatus HTTP status to return
     * @param message    client-safe message
     * @param details    field errors (field, rejected, message); copied defensively
     */

    public AdminAuthenticationException(ErrorCode code,  int httpStatus, String message, List<Map<String,
            String>> details){
       super(message, code, false);
       this.httpStatus= httpStatus;
       this.details= List.copyOf(details);
    }
    /**
     * Builds one field-level detail entry. The rejected value is never echoed (Rule 16.6).
     *
     * @param field   field name
     * @param message what is wrong, without the submitted value
     * @return detail entry with field, rejected and message
     */
    public static Map<String, String> fieldDetail(String field, String message) {
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("field", field);
        detail.put("rejected", AdminAuthConstants.REDACTED);
        detail.put("message", message);
        return detail;
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
        Map<String, Object> error= new LinkedHashMap<>();
        error.put(KEY_CODE, code().name());
        error.put(KEY_MESSAGE, getMessage());
        error.put(KEY_RETRYABLE, false);
        if(!details.isEmpty()){
            error.put(KEY_DETAILS, details);
        }
        return Map.<String, Object>of(KEY_ERROR, error);
    }

}
