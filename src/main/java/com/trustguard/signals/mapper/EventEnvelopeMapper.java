package com.trustguard.signals.mapper;

import java.util.Map;
import java.util.Objects;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.SessionIdRequiredException;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.IngestionContext;
import com.trustguard.signals.dto.TrustEventDto;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;
import com.trustguard.signals.util.PayloadSnapshot;

/**
 * Maps a validated client event to the platform-side envelope (Rules 6.1 and 6.3, Rulings 54, 70 to
 * 72 and 74). Pure and stateless: no Spring, no I/O.
 *
 * <p>Preconditions. The DTO has passed Bean Validation. So the payload is serializable and free of
 * NUL and unpaired surrogates, USER and SERVICE events have an actorId, and a tenant idempotencyKey
 * is stored exactly as sent. The mapper never validates formats (Rule 16.5).
 *
 * <p>Tenant, project, event id and receivedAt come only from the {@link IngestionContext}; the
 * client's projectId is never read. For an ANONYMOUS actor the actor id is always the generated one
 * and any supplied actorId is ignored. For USER and SERVICE the actorId is used as given. The session
 * id is dropped for SERVICE actors. A non-blank tenant idempotency key is kept as sent; a null or
 * blank one gets the fallback, computed with the final actor id.
 *
 * <p>The payload is read from the DTO once and frozen into a snapshot (Ruling 74). The fallback key
 * and the envelope both come from that snapshot, so the key is a pure function of what the envelope
 * stores.
 *
 * <p>A null argument is a programming error and throws {@link NullPointerException}, as does a USER
 * or SERVICE event without an actorId. An actorId that {@link ActorId} itself rejects can only come
 * from a DTO that skipped validation. Messages never contain client data. Thread-safe once
 * constructed.
 */
public final class EventEnvelopeMapper {

    private final AnonymousActorIdGenerator anonymousActorIdGenerator;
    private final IdempotencyKeyGenerator idempotencyKeyGenerator;

    /**
     * Creates the mapper.
     *
     * @param anonymousActorIdGenerator computes the actor id of ANONYMOUS events
     * @param idempotencyKeyGenerator   computes the fallback idempotency key
     */
    public EventEnvelopeMapper(AnonymousActorIdGenerator anonymousActorIdGenerator,
                               IdempotencyKeyGenerator idempotencyKeyGenerator) {
        this.anonymousActorIdGenerator =
                Objects.requireNonNull(anonymousActorIdGenerator, "anonymousActorIdGenerator");
        this.idempotencyKeyGenerator = Objects.requireNonNull(idempotencyKeyGenerator, "idempotencyKeyGenerator");
    }

    /**
     * Maps a validated event to its envelope.
     *
     * @param dto     the validated client event
     * @param context the platform-owned facts: tenant, project, event id and receive time
     * @return the envelope, with a deep unmodifiable payload snapshot
     * @throws SessionIdRequiredException   if an ANONYMOUS event has no usable session id
     * @throws InvalidEventPayloadException if the payload or an identity field cannot be encoded, or
     *                                      the payload nests too deep, holds too many values or holds
     *                                      an unsupported value
     */
    public EventEnvelope map(TrustEventDto dto, IngestionContext context) {
        Objects.requireNonNull(dto, "dto");
        Objects.requireNonNull(context, "context");
        ActorType actorType = Objects.requireNonNull(dto.actorType(), "actorType");
        ActorId actorId = resolveActorId(dto, actorType, context);
        Map<String, Object> payload = PayloadSnapshot.copyOf(dto.payload());
        String idempotencyKey = resolveIdempotencyKey(dto, actorId, context, payload);
        return EventEnvelope.builder()
                .eventId(context.eventId())
                .tenantId(context.tenantId())
                .projectId(context.projectId())
                .actorType(actorType)
                .actorId(actorId)
                .sessionId(sessionIdFor(dto, actorType))
                .eventType(dto.eventType())
                .environment(dto.environment())
                .occurredAt(dto.occurredAt())
                .receivedAt(context.receivedAt())
                .sdkVersion(dto.sdkVersion())
                .ipAddress(dto.ipAddress())
                .userAgent(dto.userAgent())
                .deviceFingerprint(dto.deviceFingerprint())
                .idempotencyKey(idempotencyKey)
                .payload(payload)
                .build();
    }

    private ActorId resolveActorId(TrustEventDto dto, ActorType actorType, IngestionContext context) {
        if (actorType == ActorType.ANONYMOUS) {
            return anonymousActorIdGenerator.generate(context.tenantId(), dto.sessionId());
        }
        String supplied = Objects.requireNonNull(dto.actorId(), SignalConstants.ACTOR_ID_REQUIRED_MESSAGE);
        return new ActorId(supplied);
    }

    private static String sessionIdFor(TrustEventDto dto, ActorType actorType) {
        return actorType == ActorType.SERVICE ? null : dto.sessionId();
    }

    private String resolveIdempotencyKey(TrustEventDto dto, ActorId actorId, IngestionContext context,
                                         Map<String, Object> payload) {
        String supplied = dto.idempotencyKey();
        if (supplied != null && !supplied.isBlank()) {
            return supplied;
        }
        return idempotencyKeyGenerator.generate(context.tenantId(), actorId, dto.eventType(),
                dto.occurredAt(), payload);
    }
}