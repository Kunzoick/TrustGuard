package com.trustguard.shared.error;
import com.trustguard.shared.enums.ErrorCode;

/**
 * RabbitMQ publish failure from the outbox poller. Retryable — Rule 9.7
 * defines an explicit exponential-backoff retry policy (2s, 4s, 8s, 16s,
 * 32s, maximum 5 attempts) for this exact operation.
 *
 * OPEN QUESTION: mapped to ErrorCode.INTERNAL_ERROR as an interim choice.
 * Rule 21.7's error code registry has no dedicated code for outbox
 * publish failures, despite Rule 17.3 requiring this exception type to
 * exist. This is a real inconsistency between the two rules, not a
 * confirmed-correct mapping — see the batch response's assumptions
 * section.
 */
public final class OutboxPublishException extends TrustGuardInfrastructureException {

    public OutboxPublishException(String message) {
        super(message, ErrorCode.OUTBOX_PUBLISH_FAILED, true);
    }

    public OutboxPublishException(String message, Throwable cause) {
        super(message, cause, ErrorCode.OUTBOX_PUBLISH_FAILED, true);
    }
}