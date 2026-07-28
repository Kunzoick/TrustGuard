package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Actor state rebuild from PostgreSQL failed or exceeded
 * computationTimeoutMs during cold-start (Rule 8.5). Retryable — Rule
 * 9.7 permits up to two rebuild attempts for this operation.
 */
public final class ActorStateBuildException extends TrustGuardInfrastructureException {

    public ActorStateBuildException(String message) {
        super(message, ErrorCode.ACTOR_STATE_BUILD_FAILED, true);
    }

    public ActorStateBuildException(String message, Throwable cause) {
        super(message, cause, ErrorCode.ACTOR_STATE_BUILD_FAILED, true);
    }
}