package com.trustguard.api.ingestion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.EnvironmentMismatchException;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.shared.error.SessionIdRequiredException;

/**
 * Exception mapping for the ingestion API (Ruling 30). It handles only the exception types below, on
 * purpose with no catch-all: an unhandled exception must still surface as 500. Every body keeps the
 * platform shape {"error":{"code","message","retryable"[,"details"]}} with the content type
 * application/json;charset=UTF-8, and never contains a submitted value (Rules 14.7 and 16.6).
 *
 * <p>Statuses: an invalid request, a missing session id and an unusable payload are 400. An event
 * whose environment differs from the API key's is 401, the same status the API key filter gives its
 * own environment mismatch, so one error code carries one status. Rule 5.3 prescribes no status.
 */
@RestControllerAdvice(basePackages = "com.trustguard.api.ingestion")
public class IngestionExceptionHandler {

    private static final String KEY_ERROR = "error";
    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_RETRYABLE = "retryable";
    private static final String KEY_DETAILS = "details";
    private static final String KEY_FIELD = "field";
    private static final String KEY_REJECTED = "rejected";
    private static final String REDACTED = "[REDACTED]";

    private static final String MESSAGE_SESSION_ID_REQUIRED = "sessionId is required for ANONYMOUS actors.";
    private static final String MESSAGE_INVALID_PAYLOAD = "Event payload cannot be processed.";
    private static final String MESSAGE_ENVIRONMENT_MISMATCH =
            "Event environment does not match the API key environment.";

    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);

    /**
     * Maps an invalid ingestion request: a malformed body, field-level failures, an oversize payload
     * field or a timestamp outside the accepted window.
     *
     * @param e the failure; its message and details are fixed text
     * @return JSON 400 response
     */
    @ExceptionHandler(InvalidIngestionRequestException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidRequest(InvalidIngestionRequestException e) {
        return response(HttpStatus.BAD_REQUEST, e.code(), e.getMessage(), e.details());
    }

    /**
     * Maps an event whose environment differs from the API key's environment.
     *
     * @param e the failure
     * @return JSON 401 response
     */
    @ExceptionHandler(EnvironmentMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleEnvironmentMismatch(EnvironmentMismatchException e) {
        return response(HttpStatus.UNAUTHORIZED, e.code(), MESSAGE_ENVIRONMENT_MISMATCH, List.of());
    }

    /**
     * Maps an ANONYMOUS event without a usable session id.
     *
     * @param e the failure
     * @return JSON 400 response
     */
    @ExceptionHandler(SessionIdRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleSessionIdRequired(SessionIdRequiredException e) {
        return response(HttpStatus.BAD_REQUEST, e.code(), MESSAGE_SESSION_ID_REQUIRED, List.of());
    }

    /**
     * Maps a payload or identity field that cannot be encoded.
     *
     * @param e the failure
     * @return JSON 400 response
     */
    @ExceptionHandler(InvalidEventPayloadException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPayload(InvalidEventPayloadException e) {
        return response(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, MESSAGE_INVALID_PAYLOAD, List.of());
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, ErrorCode code, String message,
                                                                List<FieldDetail> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put(KEY_CODE, code.name());
        error.put(KEY_MESSAGE, message);
        error.put(KEY_RETRYABLE, false);
        if (!details.isEmpty()) {
            error.put(KEY_DETAILS, render(details));
        }
        return ResponseEntity.status(status).contentType(JSON_UTF8).body(Map.of(KEY_ERROR, error));
    }

    private static List<Map<String, String>> render(List<FieldDetail> details) {
        List<Map<String, String>> rendered = new ArrayList<>();
        for (FieldDetail detail : details) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put(KEY_FIELD, detail.field());
            entry.put(KEY_REJECTED, REDACTED);
            entry.put(KEY_MESSAGE, detail.message());
            rendered.add(entry);
        }
        return rendered;
    }
}