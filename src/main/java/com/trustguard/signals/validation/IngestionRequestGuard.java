package com.trustguard.signals.validation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.EnvironmentMismatchException;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.dto.TrustEventDto;
import com.trustguard.tenant.context.TenantContext;

/**
 * Business guards on a validated ingestion event (Rules 5.3 and 6.2, Ruling 79). Stateless and
 * thread-safe.
 *
 * <p>The event's environment must equal the authenticated key's environment, otherwise
 * {@link EnvironmentMismatchException}. Then occurredAt must lie inside the accepted window around
 * receivedAt: more than five minutes after it is TIMESTAMP_TOO_FAR_FUTURE, more than seven days
 * before it is TIMESTAMP_TOO_OLD. Exactly five minutes ahead and exactly seven days back are
 * accepted. Time comes only from the receivedAt argument, never from a second clock reading. The
 * tenant-configurable window does not exist yet. The client's projectId is not checked here.
 *
 * <p>Messages are fixed text and never contain a submitted value (Rule 16.6).
 */
public final class IngestionRequestGuard {
    private static final Duration MAX_FUTURE_SKEW= Duration.ofMinutes(5);
    private static final Duration MAX_EVENT_AGE= Duration.ofDays(7);
    private static final String FIELD_OCCURRED_AT= "occurredAt";
    private static final String MESSAGE_ENVIRONMENT_MISMATCH =
            "Event environment does not match the API key environment.";
    private static final String MESSAGE_TOO_FAR_FUTURE = "occurredAt is too far in the future.";
    private static final String MESSAGE_TOO_OLD = "occurredAt is older than the accepted window.";
    /**
     * Checks the environment and the timestamp window.
     *
     * @param dto        the validated event, with environment and occurredAt present
     * @param tenant     the authenticated tenant context, from the API key only
     * @param receivedAt when the platform received the request
     * @throws EnvironmentMismatchException     if the event's environment differs from the key's
     * @throws InvalidIngestionRequestException if occurredAt is too far ahead or too old
     */
    public void check(TrustEventDto dto,  TenantContext tenant, Instant receivedAt){
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(tenant,"tenant");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Environment eventEnviroment= Objects.requireNonNull(dto.environment(), "environment");
        if(eventEnviroment != tenant.environment()){
            throw new EnvironmentMismatchException(MESSAGE_ENVIRONMENT_MISMATCH);
        }
        Instant occurredAt= Objects.requireNonNull(dto.occurredAt(), "occurredAt");
        if(occurredAt.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))){
            throw rejected(ErrorCode.TIMESTAMP_TOO_FAR_FUTURE, MESSAGE_TOO_FAR_FUTURE);
        }
        if(occurredAt.isBefore(receivedAt.minus(MAX_EVENT_AGE))){
            throw rejected(ErrorCode.TIMESTAMP_TOO_OLD, MESSAGE_TOO_OLD);
        }
    }
    private static InvalidIngestionRequestException rejected(ErrorCode code,  String message){
        return new InvalidIngestionRequestException(code, message,
                List.of(new FieldDetail(FIELD_OCCURRED_AT, message)));
    }
}
