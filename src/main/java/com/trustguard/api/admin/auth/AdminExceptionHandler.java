package com.trustguard.api.admin.auth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.trustguard.shared.enums.ErrorCode;

/**
 * The single exception mapping for the admin API (RULING 27). Handles only the exception types below, on
 * purpose with no catch-all: an unhandled exception must still surface as 500 (RULING 20), and a catch-all
 * here would hide it. Every body keeps the B-007 shape (RULING 30) and never echoes a submitted value.
 */
@RestControllerAdvice(basePackages = "com.trustguard.api.admin")
public class AdminExceptionHandler {
    private static final String MESSAGE_VALIDATION_FAILED= "Request validation failed.";
    private static final String MESSAGE_MALFORMED_BODY= "Malformed request body";
    private static final String MESSAGE_INVLAID_FORMAT= "has an invalid format";
    /**
     * Maps admin failures (401, 400 and 404) to their own status and body.
     *
     * @param e the admin failure
     * @return JSON response
     */
    @ExceptionHandler(AdminAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAdminFailure(AdminAuthenticationException e){
        return ResponseEntity.status(e.httpStatus()).body(e.toResponseBody());
    }
    /**
     * Maps DTO validation failures, with every field error and a redacted rejected value.
     *
     * @param e the validation failure
     * @return JSON 400 response
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e){
        List<Map<String, String>> details= new ArrayList<>();
        for(FieldError fieldError : e.getBindingResult().getFieldErrors()){
            details.add(AdminAuthenticationException.fieldDetail(fieldError.getField(),
                    fieldError.getDefaultMessage()));
        }
        return badRequest(MESSAGE_VALIDATION_FAILED, details);
    }
    /**
     * Maps unreadable request bodies.
     *
     * @param e the parse failure
     * @return JSON 400 response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreachable(HttpMessageNotReadableException e){
        return badRequest(MESSAGE_MALFORMED_BODY, List.of());
    }
    /**
     * Maps path variables that cannot be converted, such as a malformed adminId.
     *
     * @param e the conversion failure
     * @return JSON 400 response
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e){
        return badRequest(MESSAGE_VALIDATION_FAILED, List.of(AdminAuthenticationException
                .fieldDetail(e.getName(), MESSAGE_INVLAID_FORMAT)));
    }
    private static ResponseEntity<Map<String, Object>> badRequest(String message, List<Map<String, String>> details){
        AdminAuthenticationException failure= new AdminAuthenticationException(ErrorCode.VALIDATION_FAILED,
                AdminAuthConstants.HTTP_BAD_REQUEST, message, details);
        return ResponseEntity.status(failure.httpStatus()).body(failure.toResponseBody());
    }
}
