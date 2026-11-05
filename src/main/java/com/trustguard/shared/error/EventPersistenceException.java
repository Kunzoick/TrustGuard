package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * An event could not be stored: the database rejected a write, or an envelope did not belong to the
 * authenticated tenant (Rule 17.3, Rulings 102 and 104). It is an infrastructure failure, so the API layer
 * maps it to a 5xx. It is retryable only when the underlying data access failure was transient.
 *
 * <p>The message is fixed text and no cause is attached. PostgreSQL error details quote the rejected row
 * values, so a cause would put client data into logs and stack traces (Rule 14.7).
 */
public final class EventPersistenceException extends TrustGuardInfrastructureException{
    //creates the exception
    public EventPersistenceException(String message, boolean retryable){
        super(message, ErrorCode.INTERNAL_ERROR, retryable);
    }
}
