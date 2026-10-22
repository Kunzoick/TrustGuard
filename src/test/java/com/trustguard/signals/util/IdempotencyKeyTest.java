package com.trustguard.signals.util;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidEventPayloadException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTest {

    private static final int KEY_BYTES = 32;
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final TenantId OTHER_TENANT = new TenantId("223e4567-e89b-12d3-a456-426614174000");
    private static final ActorId ACTOR = new ActorId("user-42");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05.123Z");

    /** Paste from b009_vectors.py (IDEM_WITH_PAYLOAD). Master key = bytes 0x00..0x1f. */
    private static final String PINNED_WITH_PAYLOAD = "c7716e660b9c5d2c6ce14828646fdbb4e4433cd77e2b7a9b2e27b5c304120220";

    /** Paste from b009_vectors.py (IDEM_EMPTY_PAYLOAD). */
    private static final String PINNED_EMPTY_PAYLOAD = "34037566a7943835d4ecba0b33b76e1e5065d779c03092c3baa05c7a7d27f9f0";

    private static byte[] key(int offset) {
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            key[i] = (byte) (i + offset);
        }
        return key;
    }

    private static IdempotencyKeyGenerator generator() {
        return new IdempotencyKeyGenerator(key(0));
    }

    /** Canonical form: {"a":1,"b":[true,null],"c":"x"}; inserted out of order on purpose. */
    private static Map<String, Object> payload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("c", "x");
        map.put("a", 1);
        map.put("b", Arrays.asList(true, null));
        return map;
    }

    private static String baseKey() {
        return generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, payload());
    }

    @Test
    @DisplayName("matches the independently computed vector for a non-empty payload")
    void matchesPinnedVectorWithPayload() {
        assertThat(baseKey()).isEqualTo(PINNED_WITH_PAYLOAD);
    }

    @Test
    @DisplayName("matches the independently computed vector for an empty payload")
    void matchesPinnedVectorWithEmptyPayload() {
        String key = generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, Map.of());

        assertThat(key).isEqualTo(PINNED_EMPTY_PAYLOAD);
    }

    @Test
    @DisplayName("a null payload is the same as an empty payload (Ruling 57)")
    void nullPayloadEqualsEmptyPayload() {
        String withNull = generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, null);
        String withEmpty = generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, Map.of());

        assertThat(withNull).isEqualTo(withEmpty);
    }

    @Test
    @DisplayName("is 64 lowercase hex characters")
    void hasTheRequiredFormat() {
        assertThat(baseKey()).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("is deterministic")
    void isDeterministic() {
        assertThat(baseKey()).isEqualTo(baseKey());
    }

    @Test
    @DisplayName("payload key order does not change the key")
    void payloadKeyOrderDoesNotMatter() {
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("b", Arrays.asList(true, null));
        reordered.put("c", "x");
        reordered.put("a", 1);

        String key = generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, reordered);

        assertThat(key).isEqualTo(baseKey());
    }

    @Test
    @DisplayName("changing any single field changes the key")
    void everyFieldChangesTheKey() {
        IdempotencyKeyGenerator generator = generator();
        Map<String, Object> changedPayload = payload();
        changedPayload.put("a", 2);

        Set<String> keys = new HashSet<>();
        keys.add(baseKey());
        keys.add(generator.generate(OTHER_TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, payload()));
        keys.add(generator.generate(TENANT, new ActorId("user-43"), EventType.LOGIN, OCCURRED_AT, payload()));
        keys.add(generator.generate(TENANT, ACTOR, EventType.TRANSACTION, OCCURRED_AT, payload()));
        keys.add(generator.generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT.plusMillis(1), payload()));
        keys.add(generator.generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, changedPayload));

        assertThat(keys).hasSize(6);
    }

    @Test
    @DisplayName("a different master key gives a different key")
    void masterKeyChangesTheKey() {
        String other = new IdempotencyKeyGenerator(key(1))
                .generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, payload());

        assertThat(other).isNotEqualTo(baseKey());
    }

    @Test
    @DisplayName("the generator does not keep a reference to the caller's key array")
    void keyArrayIsCopied() {
        byte[] key = key(0);
        IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator(key);
        String before = generator.generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, payload());

        Arrays.fill(key, (byte) 0);

        assertThat(generator.generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, payload())).isEqualTo(before);
    }

    @Test
    @DisplayName("the length-prefixed encoding keeps (a, bLOGIN) and (ab, LOGIN) apart")
    void encodingIsInjective() {
        assertThat(IdempotencyKeyGenerator.encodeFields("a", "bLOGIN"))
                .isNotEqualTo(IdempotencyKeyGenerator.encodeFields("ab", "LOGIN"));
        assertThat(IdempotencyKeyGenerator.encodeFields("", "a"))
                .isNotEqualTo(IdempotencyKeyGenerator.encodeFields("a", ""));
    }

    @Test
    @DisplayName("each field is a 4-byte big-endian length followed by its bytes")
    void encodingLayout() {
        assertThat(IdempotencyKeyGenerator.encodeFields("a"))
                .containsExactly((byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 'a');
    }

    @Test
    @DisplayName("the length prefix counts UTF-8 bytes, not characters")
    void lengthIsInBytes() {
        assertThat(IdempotencyKeyGenerator.encodeFields("\u00e9"))
                .containsExactly((byte) 0, (byte) 0, (byte) 0, (byte) 2, (byte) 0xC3, (byte) 0xA9);
    }

    @Test
    @DisplayName("encoding a field with an unpaired surrogate fails instead of writing a question mark")
    void encodingRejectsLoneSurrogate() {
        String bad = "x" + (char) 0xD800;

        assertThatThrownBy(() -> IdempotencyKeyGenerator.encodeFields(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an actor id with an unpaired surrogate is rejected, and '?' gives a different key")
    void loneSurrogateActorIdIsRejected() {
        IdempotencyKeyGenerator generator = generator();
        ActorId bad = new ActorId("user-" + (char) 0xD800);
        ActorId questionMark = new ActorId("user-?");
        ActorId plain = new ActorId("user-");

        assertThatThrownBy(() -> generator.generate(TENANT, bad, EventType.LOGIN, OCCURRED_AT, Map.of()))
                .isInstanceOfSatisfying(InvalidEventPayloadException.class, thrown -> {
                    assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(thrown.getMessage()).doesNotContain("user-");
                });
        assertThat(generator.generate(TENANT, questionMark, EventType.LOGIN, OCCURRED_AT, Map.of()))
                .isNotEqualTo(generator.generate(TENANT, plain, EventType.LOGIN, OCCURRED_AT, Map.of()));
    }

    @Test
    @DisplayName("a payload that cannot be canonicalised surfaces as InvalidEventPayloadException")
    void unserializablePayloadIsWrapped() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("score", Double.NaN);

        assertThatThrownBy(() -> generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, bad))
                .isInstanceOfSatisfying(InvalidEventPayloadException.class, thrown -> {
                    assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(thrown.getCause()).isInstanceOf(IllegalArgumentException.class);
                    assertThat(thrown.getMessage()).doesNotContain("score").doesNotContain("NaN");
                });
    }

    @Test
    @DisplayName("an oversize payload is wrapped too and keeps the output-limit exception as its cause")
    void oversizePayloadIsWrapped() {
        Map<String, Object> big = new LinkedHashMap<>();
        big.put("text", "a".repeat(40_000));

        assertThatThrownBy(() -> generator().generate(TENANT, ACTOR, EventType.LOGIN, OCCURRED_AT, big))
                .isInstanceOfSatisfying(InvalidEventPayloadException.class, thrown ->
                        assertThat(thrown.getCause())
                                .isInstanceOf(CanonicalJsonSerializer.OutputLimitExceededException.class));
    }

    @Test
    @DisplayName("a null master key is rejected at construction")
    void nullMasterKeyIsRejected() {
        assertThatThrownBy(() -> new IdempotencyKeyGenerator(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a master key shorter than 32 bytes is rejected at construction")
    void shortMasterKeyIsRejected() {
        byte[] shortKey = new byte[KEY_BYTES - 1];

        assertThatThrownBy(() -> new IdempotencyKeyGenerator(shortKey)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a null tenant is a programming error")
    void nullTenantIsRejected() {
        IdempotencyKeyGenerator generator = generator();

        assertThatThrownBy(() -> generator.generate(null, ACTOR, EventType.LOGIN, OCCURRED_AT, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}