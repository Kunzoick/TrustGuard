package com.trustguard.signals.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.util.PayloadSnapshot;

import lombok.Builder;

/**
 * The platform-side event envelope (Rule 6.1): the client's fields plus what only the platform
 * knows. B-010 stores it. There is no riskContextSnapshot: it is null at ingest (Rule 6.7).
 *
 * <p>The compact constructor requires every component except sessionId, userAgent and
 * deviceFingerprint, and replaces the payload with a deep unmodifiable snapshot (Ruling 69), so
 * any construction path is safe. A null payload becomes an empty map (Ruling 57). It checks that
 * required fields are present, never their format (Rule 16.5). {@link #toString()} is redacted
 * (Rule 14.7).
 *
 * @param eventId           the platform-generated event id
 * @param tenantId          the authenticated tenant
 * @param projectId         the project the event belongs to
 * @param actorType         the actor kind
 * @param actorId           the actor id; computed by the platform for ANONYMOUS actors
 * @param sessionId         the client session id; null for SERVICE actors
 * @param eventType         the event type
 * @param environment       the deployment environment
 * @param occurredAt        when the event happened on the client
 * @param receivedAt        when the platform received the event, from the ingestion context
 * @param sdkVersion        the sdk version string
 * @param ipAddress         the client ip address as sent
 * @param userAgent         the client user agent, or null
 * @param deviceFingerprint the client device fingerprint, or null
 * @param idempotencyKey    the tenant's key as sent, or the platform-generated fallback
 * @param payload           a deep unmodifiable snapshot of the event payload, never null
 */
@Builder
public record EventEnvelope(
        EventId eventId,
        TenantId tenantId,
        ProjectId projectId,
        ActorType actorType,
        ActorId actorId,
        String sessionId,
        EventType eventType,
        Environment environment,
        Instant occurredAt,
        Instant receivedAt,
        String sdkVersion,
        String ipAddress,
        String userAgent,
        String deviceFingerprint,
        String idempotencyKey,
        Map<String, Object> payload) {

    /**
     * Rejects a missing required component and snapshots the payload.
     */
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(actorType, "actorType");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(sdkVersion, "sdkVersion");
        Objects.requireNonNull(ipAddress, "ipAddress");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        payload = PayloadSnapshot.copyOf(payload);
    }

    /**
     * Redacted: only ids, the event and actor kind, the environment and the two timestamps. It
     * never prints the ip address, user agent, device fingerprint, session id, actor id,
     * idempotency key, sdk version or payload (Rule 14.7).
     *
     * @return a short description without client data
     */
    @Override
    public String toString() {
        return "EventEnvelope[eventId=" + eventId + ", tenantId=" + tenantId + ", projectId=" + projectId
                + ", eventType=" + eventType + ", actorType=" + actorType + ", environment=" + environment
                + ", occurredAt=" + occurredAt + ", receivedAt=" + receivedAt + "]";
    }
}