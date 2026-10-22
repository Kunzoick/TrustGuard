package com.trustguard.signals.util;
import java.util.Objects;

import javax.crypto.SecretKey;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.SessionIdRequiredException;
import com.trustguard.signals.constants.SignalConstants;

/**
 * Computes the platform-side actor id of an ANONYMOUS actor (Rule 6.3,
 * Ruling 51): {@code anon_} followed by the 64 lowercase hex characters of
 * HMAC-SHA256(anonSubkey, tenantId + sessionId), never truncated. The
 * tenant id is the 36-character lowercase UUID, so the concatenation is
 * unambiguous. The session id is not trimmed or normalised. It is encoded
 * strictly (Ruling 63): a session id holding an unpaired surrogate is
 * rejected, never replaced, so it cannot collide with another actor.
 *
 * <p>Thread-safe and immutable. Contract: a master key that is null or
 * shorter than {@link SignalConstants#MIN_MASTER_KEY_BYTES} makes the
 * constructor throw {@link IllegalStateException} (startup gate, Ruling 2).
 * Rotating the master key changes every anonymous actor id (Ruling 50).
 */
public final class AnonymousActorIdGenerator {

    private final SecretKey subkey;

    /**
     * Creates the generator.
     *
     * @param masterKey the decoded EVENT_HMAC_KEY bytes; copied, not retained
     * @throws IllegalStateException if the key is null or too short
     */
    public AnonymousActorIdGenerator(byte[] masterKey) {
        this.subkey = HmacSha256.deriveSubkey(masterKey, SignalConstants.ANON_SUBKEY_LABEL);
    }

    /**
     * Computes the anonymous actor id.
     *
     * @param tenantId  the tenant, not null
     * @param sessionId the client session id, not null and not blank
     * @return the actor id, 69 characters
     * @throws SessionIdRequiredException   if the session id is null or blank
     * @throws InvalidEventPayloadException if the session id holds an unpaired surrogate
     */
    public ActorId generate(TenantId tenantId, String sessionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new SessionIdRequiredException("sessionId is required for an ANONYMOUS actor");
        }
        byte[] message;
        try {
            message = HmacSha256.utf8(tenantId.value() + sessionId);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventPayloadException("sessionId is not valid Unicode text", ex);
        }
        return new ActorId(SignalConstants.ANON_ACTOR_ID_PREFIX + HmacSha256.hmacHex(subkey, message));
    }
}