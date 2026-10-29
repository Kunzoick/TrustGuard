package com.trustguard.signals.config;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class EventHmacConfigTest {

    private static final String PROPERTY = "trustguard.events.hmac-key";
    private static final int MIN_KEY_BYTES = 32;
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05.123Z");

    private static final String MESSAGE_MISSING = "EVENT_HMAC_KEY is missing or blank.";
    private static final String MESSAGE_NOT_BASE64 = "EVENT_HMAC_KEY is not valid Base64.";
    private static final String MESSAGE_TOO_SHORT = "EVENT_HMAC_KEY must decode to at least 32 bytes.";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EventHmacConfig.class);

    private static byte[] key(int length) {
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static String base64(int length) {
        return Base64.getEncoder().encodeToString(key(length));
    }

    private void assertRejected(String value, String expectedMessage, String mustNotLeak) {
        runner.withPropertyValues(PROPERTY + "=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(expectedMessage);
            assertThat(context.getStartupFailure().getMessage()).doesNotContain(mustNotLeak);
        });
    }

    @Test
    @DisplayName("a valid key of exactly 32 bytes starts both generators")
    void aValidKeyStartsBothGenerators() {
        runner.withPropertyValues(PROPERTY + "=" + base64(MIN_KEY_BYTES)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AnonymousActorIdGenerator.class);
            assertThat(context).hasSingleBean(IdempotencyKeyGenerator.class);
        });
    }

    @Test
    @DisplayName("a longer key is accepted")
    void aLongerKeyIsAccepted() {
        runner.withPropertyValues(PROPERTY + "=" + base64(64)).run(context ->
                assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("a key one byte short stops the application with a fixed message")
    void aKeyOneByteShortIsRejected() {
        String shortKey = base64(MIN_KEY_BYTES - 1);

        assertRejected(shortKey, MESSAGE_TOO_SHORT, shortKey);
    }

    @Test
    @DisplayName("both generators are built from the same decoded key")
    void bothGeneratorsUseTheSameKey() {
        byte[] raw = key(MIN_KEY_BYTES);
        runner.withPropertyValues(PROPERTY + "=" + Base64.getEncoder().encodeToString(raw)).run(context -> {
            AnonymousActorIdGenerator anonymous = context.getBean(AnonymousActorIdGenerator.class);
            IdempotencyKeyGenerator idempotency = context.getBean(IdempotencyKeyGenerator.class);
            Map<String, Object> payload = Map.of("a", 1);

            assertThat(anonymous.generate(TENANT, "session-1"))
                    .isEqualTo(new AnonymousActorIdGenerator(raw).generate(TENANT, "session-1"));
            assertThat(idempotency.generate(TENANT, new ActorId("user-1"), EventType.LOGIN, OCCURRED_AT, payload))
                    .isEqualTo(new IdempotencyKeyGenerator(raw)
                            .generate(TENANT, new ActorId("user-1"), EventType.LOGIN, OCCURRED_AT, payload));
        });
    }

    @Test
    @DisplayName("a missing property stops the application")
    void aMissingKeyIsRejected() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(MESSAGE_MISSING);
        });
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("an empty or blank value stops the application")
    void aBlankKeyIsRejected(String blank) {
        assertRejected(blank, MESSAGE_MISSING, "unlikely-to-appear");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"secret!value", "not base64", "AAAA-AAAA_AAAA"})
    @DisplayName("text that is not standard Base64, including the URL-safe alphabet, is rejected without echoing it")
    void aValueThatIsNotBase64IsRejected(String notBase64) {
        assertRejected(notBase64, MESSAGE_NOT_BASE64, notBase64);
    }

    @Test
    @DisplayName("no event envelope mapper bean is exposed (Ruling 82)")
    void noMapperBeanIsExposed() {
        runner.withPropertyValues(PROPERTY + "=" + base64(MIN_KEY_BYTES)).run(context ->
                assertThat(context.getBeansOfType(Object.class).values().stream()
                        .map(bean -> bean.getClass().getSimpleName()))
                        .doesNotContain("EventEnvelopeMapper"));
    }
}