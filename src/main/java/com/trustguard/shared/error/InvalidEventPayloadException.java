package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * An event payload cannot be turned into canonical JSON: it holds a value
 * of an unsupported type, a non-finite number, a number whose exponent is
 * beyond the platform bound, a non-string object key, or nesting deeper
 * than the platform bound (Rule 6.4, Ruling 56). Not retryable: the client
 * must change the payload.
 *
 * <p>Messages describe the reason only. They never contain payload values
 * or keys (Rule 16.6).
 */
public class InvalidEventPayloadException extends TrustGuardException{
    /**
     * creates the exception
     * @param message the reason, free of payload content
     */
    public InvalidEventPayloadException(String message){
        super(message, ErrorCode.VALIDATION_FAILED, false);
    }
    /**
     * Creates the exception with the underlying cause.
     *
     * @param message the reason, free of payload content
     * @param cause   the internal failure that triggered this exception
     */
    public InvalidEventPayloadException(String message, Throwable cause){
        super(message, cause, ErrorCode.VALIDATION_FAILED, false);
    }
}
