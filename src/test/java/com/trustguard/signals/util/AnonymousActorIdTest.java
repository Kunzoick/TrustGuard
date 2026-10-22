package com.trustguard.signals.util;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.SessionIdRequiredException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnonymousActorIdTest {

    private static final int KEY_BYTES = 32;
    private static final String PREFIX = "anon_";
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final TenantId OTHER_TENANT = new TenantId("223e4567-e89b-12d3-a456-426614174000");
    private static final String SESSION = "session-abc";
    private static final String UNICODE_SESSION = "s\u00e9ssion-\u2713";

    /** Paste from b009_vectors.py (ANON_ASCII). Master key = bytes 0x00..0x1f. */
    private static final String PINNED_ASCII = "anon_d8a4dfe60b753f7d92b8f59ced12351da2298952020c9b7baf18fa5ed9e68273";

    /** Paste from b009_vectors.py (ANON_UNICODE). */
    private static final String PINNED_UNICODE = "anon_9ede284498fd2f9877374d550cdf888c0ac8eba1dadf8d7971209666b876be22";

    private static byte[] key(int offset) {
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            key[i] = (byte) (i + offset);
        }
        return key;
    }

    private static AnonymousActorIdGenerator generator() {
        return new AnonymousActorIdGenerator(key(0));
    }

    @Test
    @DisplayName("matches the independently computed vector for an ASCII session id")
    void matchesPinnedAsciiVector() {
        assertThat(generator().generate(TENANT, SESSION).value()).isEqualTo(PINNED_ASCII);
    }

    @Test
    @DisplayName("matches the independently computed vector for a non-ASCII session id (UTF-8)")
    void matchesPinnedUnicodeVector() {
        assertThat(generator().generate(TENANT, UNICODE_SESSION).value()).isEqualTo(PINNED_UNICODE);
    }

    @Test
    @DisplayName("is anon_ plus 64 lowercase hex characters, 69 in total")
    void hasTheRequiredFormat() {
        String id = generator().generate(TENANT, SESSION).value();

        assertThat(id).matches("anon_[0-9a-f]{64}");
        assertThat(id).hasSize(PREFIX.length() + 64);
    }

    @Test
    @DisplayName("is deterministic")
    void isDeterministic() {
        assertThat(generator().generate(TENANT, SESSION)).isEqualTo(generator().generate(TENANT, SESSION));
    }

    @Test
    @DisplayName("a different tenant gives a different id")
    void tenantChangesTheId() {
        assertThat(generator().generate(TENANT, SESSION)).isNotEqualTo(generator().generate(OTHER_TENANT, SESSION));
    }

    @Test
    @DisplayName("a different session gives a different id")
    void sessionChangesTheId() {
        assertThat(generator().generate(TENANT, SESSION)).isNotEqualTo(generator().generate(TENANT, "session-abd"));
    }

    @Test
    @DisplayName("the session id is not trimmed, so surrounding whitespace is a different actor")
    void sessionIsNotTrimmed() {
        ActorId plain = generator().generate(TENANT, "abc");
        ActorId padded = generator().generate(TENANT, " abc");

        assertThat(padded).isNotEqualTo(plain);
    }

    @Test
    @DisplayName("a different master key gives a different id")
    void masterKeyChangesTheId() {
        ActorId other = new AnonymousActorIdGenerator(key(1)).generate(TENANT, SESSION);

        assertThat(other).isNotEqualTo(generator().generate(TENANT, SESSION));
    }

    @Test
    @DisplayName("a null, empty or blank session id throws SessionIdRequiredException")
    void missingSessionIdIsRejected() {
        AnonymousActorIdGenerator generator = generator();
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> generator.generate(TENANT, missing))
                    .isInstanceOfSatisfying(SessionIdRequiredException.class,
                            thrown -> assertThat(thrown.code()).isEqualTo(ErrorCode.SESSION_ID_REQUIRED));
        }
    }

    @Test
    @DisplayName("a session id with an unpaired surrogate is rejected and the message does not echo it")
    void loneSurrogateSessionIdIsRejected() {
        AnonymousActorIdGenerator generator = generator();
        String bad = "secret-session" + (char) 0xD800;

        assertThatThrownBy(() -> generator.generate(TENANT, bad))
                .isInstanceOfSatisfying(InvalidEventPayloadException.class, thrown -> {
                    assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(thrown.getMessage()).doesNotContain("secret-session");
                });
    }

    @Test
    @DisplayName("a question mark is accepted and gives a different id from a lone surrogate's old replacement")
    void questionMarkIsAcceptedAndDistinct() {
        AnonymousActorIdGenerator generator = generator();

        ActorId withQuestionMark = generator.generate(TENANT, "ab?");
        ActorId withoutIt = generator.generate(TENANT, "ab");

        assertThat(withQuestionMark).isNotEqualTo(withoutIt);
        assertThatThrownBy(() -> generator.generate(TENANT, "ab" + (char) 0xD800))
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    @DisplayName("a valid surrogate pair in the session id works")
    void validSurrogatePairIsAccepted() {
        String emoji = new String(Character.toChars(0x1F600));

        assertThat(generator().generate(TENANT, "s-" + emoji).value()).matches("anon_[0-9a-f]{64}");
    }

    @Test
    @DisplayName("a null master key is rejected at construction")
    void nullMasterKeyIsRejected() {
        assertThatThrownBy(() -> new AnonymousActorIdGenerator(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a master key shorter than 32 bytes is rejected at construction")
    void shortMasterKeyIsRejected() {
        byte[] shortKey = new byte[KEY_BYTES - 1];

        assertThatThrownBy(() -> new AnonymousActorIdGenerator(shortKey)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the generator does not keep a reference to the caller's key array")
    void keyArrayIsCopied() {
        byte[] key = key(0);
        AnonymousActorIdGenerator generator = new AnonymousActorIdGenerator(key);
        ActorId before = generator.generate(TENANT, SESSION);

        Arrays.fill(key, (byte) 0);

        assertThat(generator.generate(TENANT, SESSION)).isEqualTo(before);
    }

    @Test
    @DisplayName("a null tenant is a programming error")
    void nullTenantIsRejected() {
        AnonymousActorIdGenerator generator = generator();

        assertThatThrownBy(() -> generator.generate(null, SESSION)).isInstanceOf(NullPointerException.class);
    }
}