package com.trustguard.signals.util;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import javax.crypto.SecretKey;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.signals.constants.SignalConstants;

/**
 * Computes the deterministic fallback idempotency key used when the tenant
 * sends none (Rule 6.4, Ruling 51): 64 lowercase hex characters of
 * HMAC-SHA256(idempotencySubkey, fields). The fields are length-prefixed
 * (4-byte big-endian length, then UTF-8 bytes) in this order: tenantId,
 * actorId, eventType name, occurredAt as {@link Instant#toString()}, and the
 * lowercase hex SHA-256 of the canonical payload bytes. The length prefix
 * makes the encoding injective, so adjacent fields can never run together
 * and produce a false duplicate. Text is encoded strictly (Ruling 63): an
 * unpaired surrogate is rejected, never replaced by a question mark.
 *
 * <p>A null payload is treated as an empty object (Ruling 57). A payload or
 * identity field that cannot be encoded surfaces as
 * {@link InvalidEventPayloadException}. Null arguments other than the
 * payload are a programming error and throw {@link NullPointerException}.
 *
 * <p>Thread-safe and immutable. A master key that is null or shorter than
 * {@link SignalConstants#MIN_MASTER_KEY_BYTES} makes the constructor throw
 * {@link IllegalStateException} (startup gate, Ruling 2).
 */
public final class IdempotencyKeyGenerator {

    private final SecretKey subkey;

    /**
     * Creates the generator.
     *
     * @param masterKey the decoded EVENT_HMAC_KEY bytes; copied, not retained
     * @throws IllegalStateException if the key is null or too short
     */
    public IdempotencyKeyGenerator(byte[] masterKey) {
        this.subkey = HmacSha256.deriveSubkey(masterKey, SignalConstants.IDEMPOTENCY_SUBKEY_LABEL);
    }

    /**
     * Computes the fallback idempotency key.
     *
     * @param tenantId   the tenant, not null
     * @param actorId    the final actor id (computed for ANONYMOUS), not null
     * @param eventType  the event type, not null
     * @param occurredAt when the event happened on the client, not null
     * @param payload    the parsed payload, or null for an empty payload
     * @return 64 lowercase hex characters
     * @throws InvalidEventPayloadException if the payload cannot be canonicalised or a
     *                                      field holds an unpaired surrogate
     */
    public String generate(TenantId tenantId, ActorId actorId, EventType eventType,
                           Instant occurredAt, Map<String, Object> payload) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Map<String, Object> effective = payload == null ? Map.of() : payload;
        byte[] message;
        try {
            String payloadHash = HmacSha256.sha256Hex(CanonicalJsonSerializer.serializeToBytes(effective));
            message = encodeFields(tenantId.value(), actorId.value(), eventType.name(),
                    occurredAt.toString(), payloadHash);
        } catch (IllegalArgumentException ex) {
            throw new InvalidEventPayloadException("Event identity or payload cannot be encoded", ex);
        }
        return HmacSha256.hmacHex(subkey, message);
    }

    /**
     * Encodes fields as 4-byte big-endian length plus strict UTF-8 bytes, in order.
     *
     * @param fields the fields, not null
     * @return the injective encoding
     * @throws IllegalArgumentException if a field holds an unpaired surrogate; callers wrap it
     */
    static byte[] encodeFields(String... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String field : fields) {
            byte[] bytes = HmacSha256.utf8(field);
            out.writeBytes(ByteBuffer.allocate(SignalConstants.LENGTH_PREFIX_BYTES).putInt(bytes.length).array());
            out.writeBytes(bytes);
        }
        return out.toByteArray();
    }
}