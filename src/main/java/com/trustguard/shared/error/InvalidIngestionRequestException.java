package com.trustguard.shared.error;
import java.util.List;
import java.util.Objects;

import com.trustguard.shared.enums.ErrorCode;

/**
 * An ingestion request that cannot be accepted: malformed or unreadable body, field-level validation
 * failures, an oversize payload field, or a timestamp outside the accepted window (Rules 6.2 and
 * 16.5, Ruling 67). Not retryable: the client must change the request. It carries no HTTP status;
 * the API layer decides that.
 *
 * <p>The message and every detail are fixed text. They never contain a submitted value, so nothing
 * from the request body can leave through this exception (Rules 14.7 and 16.6).
 */
public final class InvalidIngestionRequestException extends TrustGuardException{
    /**
     * One field-level problem: the name of one of our own fields and a fixed description.
     *
     * @param field   the field or property path, never client text
     * @param message what is wrong, never a submitted value
     */
    public record FieldDetail(String field, String message){
        //rejects a missing field name or message
        public FieldDetail{
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(message, "message");
        }
    }
    private final List<FieldDetail> details;
    public InvalidIngestionRequestException(ErrorCode code,  String message, List<FieldDetail> details){
        super(message, code, false);
        this.details= List.copyOf(details);
    }
    public InvalidIngestionRequestException(ErrorCode code, String message){
        this(code, message, List.of());
    }
    public List<FieldDetail> details(){
        return List.copyOf(details);
    }
}
