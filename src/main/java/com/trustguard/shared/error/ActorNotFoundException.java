package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * No actor state exists for the given tenant and actorId. Not retryable-> the actor genuinely does not exist;
 * retrying the same request cannot change that.
 */

public final class ActorNotFoundException extends TrustGuardException {
    public ActorNotFoundException(String message) {
        super(message, ErrorCode.ACTOR_NOT_FOUND, false);
    }
    public ActorNotFoundException(String message, Throwable cause) {
        super(message, cause, ErrorCode.ACTOR_NOT_FOUND, false);
    }
}
