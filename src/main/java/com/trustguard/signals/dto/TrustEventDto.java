package com.trustguard.signals.dto;

import java.time.Instant;
import java.util.Map;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;

/**
 * A client-submitted behavioral event (Rule 6.1, Ruling 53). Sealed: the three records
 * are the only implementations. tenantId, eventId and receivedAt are never client fields.
 * Every accessor returns the value exactly as submitted; the platform fills the rest in
 * the mapper (B-009c).
 */
public sealed interface TrustEventDto permits LoginEventDto, TransactionEventDto, ContentSubmitEventDto {

    /**
     * The event type, a constant of the implementing record and never a client field.
     *
     * @return the event type
     */
    EventType eventType();

    /**
     * The actor kind.
     *
     * @return the actor type
     */
    ActorType actorType();

    /**
     * The tenant's own actor id, absent for ANONYMOUS actors.
     *
     * @return the actor id, or null
     */
    String actorId();

    /**
     * The client session id.
     *
     * @return the session id, or null
     */
    String sessionId();

    /**
     * When the event happened on the client.
     *
     * @return the timestamp
     */
    Instant occurredAt();

    /**
     * The sdk version string.
     *
     * @return the sdk version
     */
    String sdkVersion();

    /**
     * The deployment environment of the event.
     *
     * @return the environment
     */
    Environment environment();

    /**
     * The client ip address as sent.
     *
     * @return the ip address
     */
    String ipAddress();

    /**
     * The client user agent.
     *
     * @return the user agent, or null
     */
    String userAgent();

    /**
     * The client device fingerprint.
     *
     * @return the device fingerprint, or null
     */
    String deviceFingerprint();

    /**
     * The event-specific payload, as parsed.
     *
     * @return the payload, or null
     */
    Map<String, Object> payload();

    /**
     * The tenant-supplied idempotency key.
     *
     * @return the idempotency key, or null
     */
    String idempotencyKey();

    /**
     * The project id as sent, validated only for UUID shape.
     *
     * @return the project id, or null
     */
    String projectId();
}