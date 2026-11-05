package com.trustguard.signals.domain;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.util.CanonicalJsonSerializer;

import lombok.Builder;

/**
 * The outbox message of a stored event (Ruling 99): a claim check, not a copy. It carries only the ids,
 * the event and actor kind, the actor id, the environment and the two timestamps. It never carries the
 * ip address, user agent, session id, device fingerprint, idempotency key or the client payload.
 * Consumers read the event row by (event_id, event_received_at).
 *
 * <p>{@link #toJson()} is canonical JSON with schemaVersion 1, so equal messages give equal text. Both
 * timestamps are truncated to microseconds, the precision PostgreSQL stores, so the receivedAt in the
 * message equals the stored partition key (Ruling 105). Construction is confined to
 * {@link #from(EventEnvelope)}.
 *
 * @param eventId     the event id
 * @param tenantId    the tenant
 * @param projectId   the project
 * @param eventType   the event type
 * @param actorType   the actor kind
 * @param actorId     the actor id
 * @param environment the environment
 * @param occurredAt  when the event happened on the client, at microsecond precision
 * @param receivedAt  when the platform received it, at microsecond precision
 */
@Builder
public record OutboxMessage(EventId eventId, TenantId tenantId, ProjectId projectId, EventType eventType,
                            ActorType actorType, ActorId actorId, Environment environment, Instant occurredAt,
                            Instant receivedAt) {
    //the version of this message layout
    public static final int SCHEMA_VERSION = 1;
    private static final String KEY_SCHEMA_VERSION = "schemaVersion";
    private static final String KEY_EVENT_ID = "eventId";
    private static final String KEY_TENANT_ID = "tenantId";
    private static final String KEY_PROJECT_ID = "projectId";
    private static final String KEY_EVENT_TYPE = "eventType";
    private static final String KEY_ACTOR_TYPE = "actorType";
    private static final String KEY_ACTOR_ID = "actorId";
    private static final String KEY_ENVIRONMENT = "environment";
    private static final String KEY_OCCURRED_AT = "occurredAt";
    private static final String KEY_RECEIVED_AT = "receivedAt";

    //rejects a missing component
    public OutboxMessage{
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }
    //builds the claim check of an envelope truncating both timestamps to mircoseconds
    public static OutboxMessage from(EventEnvelope envelope){
        Objects.requireNonNull(envelope, "envelope");
        return OutboxMessage.builder().eventId(envelope.eventId()).tenantId(envelope.tenantId())
                .projectId(envelope.projectId()).eventType(envelope.eventType()).actorType(envelope.actorType())
                .actorId(envelope.actorId()).environment(envelope.environment()).occurredAt(envelope.occurredAt()
                        .truncatedTo(ChronoUnit.MICROS)).receivedAt(envelope.receivedAt()
                        .truncatedTo(ChronoUnit.MICROS)).build();
    }
    //renders the message as canonical JSON text
    public String toJson(){
        Map<String, Object> fields= new LinkedHashMap<>();
        fields.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION);
        fields.put(KEY_EVENT_ID, eventId.value());
        fields.put(KEY_TENANT_ID, tenantId.value());
        fields.put(KEY_PROJECT_ID, projectId.value());
        fields.put(KEY_EVENT_TYPE, eventType.name());
        fields.put(KEY_ACTOR_TYPE, actorType.name());
        fields.put(KEY_ACTOR_ID, actorId.value());
        fields.put(KEY_ENVIRONMENT, environment.name());
        fields.put(KEY_OCCURRED_AT, occurredAt.toString());
        fields.put(KEY_RECEIVED_AT, receivedAt.toString());
        return CanonicalJsonSerializer.serialize(fields);
    }
}
