package com.trustguard.signals.validation;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.EnvironmentMismatchException;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.dto.EventDtoFixture;
import com.trustguard.signals.dto.TrustEventDto;
import com.trustguard.tenant.context.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionRequestGuardTest {

    private static final IngestionRequestGuard GUARD = new IngestionRequestGuard();

    private static final Instant RECEIVED_AT = Instant.parse("2026-03-10T12:00:00Z");
    private static final Duration FUTURE_LIMIT = Duration.ofMinutes(5);
    private static final Duration AGE_LIMIT = Duration.ofDays(7);
    private static final Duration LATER = Duration.ofDays(8);

    private static final String MESSAGE_ENVIRONMENT = "Event environment does not match the API key environment.";
    private static final String MESSAGE_FUTURE = "occurredAt is too far in the future.";
    private static final String MESSAGE_OLD = "occurredAt is older than the accepted window.";
    private static final String FIELD_OCCURRED_AT = "occurredAt";

    // ---------- sources and helpers ----------

    static Stream<Class<? extends TrustEventDto>> types() {
        return EventDtoFixture.TYPES.stream();
    }

    static Stream<Arguments> mismatchedEnvironments() {
        List<Arguments> pairs = new ArrayList<>();
        for (Environment event : Environment.values()) {
            for (Environment key : Environment.values()) {
                if (event != key) {
                    pairs.add(Arguments.of(event, key));
                }
            }
        }
        return pairs.stream();
    }

    private static TenantContext tenant(Environment environment) {
        return new TenantContext(new TenantId(UUID.randomUUID().toString()),
                new ProjectId(UUID.randomUUID().toString()), "test-key-id", environment,
                Set.of(Capability.TRACK), 1);
    }

    private static TrustEventDto event(Class<? extends TrustEventDto> type, Environment environment,
                                       Instant occurredAt) {
        return EventDtoFixture.builder().environment(environment).occurredAt(occurredAt).build(type);
    }

    private static void check(Class<? extends TrustEventDto> type, Instant occurredAt, Instant receivedAt) {
        GUARD.check(event(type, Environment.PRODUCTION, occurredAt), tenant(Environment.PRODUCTION), receivedAt);
    }

    private static InvalidIngestionRequestException rejection(Runnable call) {
        try {
            call.run();
        } catch (InvalidIngestionRequestException ex) {
            return ex;
        }
        throw new AssertionError("Expected the request to be rejected");
    }

    // ---------- environment (Rule 5.3, Ruling 79) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an event whose environment equals the key's environment passes")
    void matchingEnvironmentPasses(Class<? extends TrustEventDto> type) {
        for (Environment environment : Environment.values()) {
            GUARD.check(event(type, environment, RECEIVED_AT.minusSeconds(60)), tenant(environment), RECEIVED_AT);
        }
    }

    @ParameterizedTest(name = "event {0} with key {1}")
    @MethodSource("mismatchedEnvironments")
    @DisplayName("every mismatching environment pair is rejected with ENVIRONMENT_MISMATCH and a fixed message")
    void mismatchingEnvironmentIsRejected(Environment eventEnvironment, Environment keyEnvironment) {
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            TrustEventDto dto = event(type, eventEnvironment, RECEIVED_AT);
            TenantContext tenant = tenant(keyEnvironment);

            assertThatThrownBy(() -> GUARD.check(dto, tenant, RECEIVED_AT))
                    .isInstanceOfSatisfying(EnvironmentMismatchException.class, thrown -> {
                        assertThat(thrown.code()).isEqualTo(ErrorCode.ENVIRONMENT_MISMATCH);
                        assertThat(thrown.retryable()).isFalse();
                        assertThat(thrown.getMessage()).isEqualTo(MESSAGE_ENVIRONMENT);
                        assertThat(thrown.getCause()).isNull();
                    });
        }
    }

    @Test
    @DisplayName("the environment is checked before the timestamp")
    void environmentIsCheckedFirst() {
        TrustEventDto dto = event(EventDtoFixture.TYPES.get(0), Environment.DEVELOPMENT,
                RECEIVED_AT.minus(AGE_LIMIT).minus(LATER));

        assertThatThrownBy(() -> GUARD.check(dto, tenant(Environment.PRODUCTION), RECEIVED_AT))
                .isInstanceOf(EnvironmentMismatchException.class);
    }

    // ---------- timestamp window (Rule 6.2) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an event inside the window passes, including one received at exactly its own time")
    void insideTheWindowPasses(Class<? extends TrustEventDto> type) {
        check(type, RECEIVED_AT, RECEIVED_AT);
        check(type, RECEIVED_AT.minusSeconds(1), RECEIVED_AT);
        check(type, RECEIVED_AT.plusSeconds(1), RECEIVED_AT);
        check(type, RECEIVED_AT.minus(Duration.ofDays(3)), RECEIVED_AT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("exactly five minutes ahead is accepted and one nanosecond more is TIMESTAMP_TOO_FAR_FUTURE")
    void futureBoundary(Class<? extends TrustEventDto> type) {
        check(type, RECEIVED_AT.plus(FUTURE_LIMIT), RECEIVED_AT);

        InvalidIngestionRequestException rejected =
                rejection(() -> check(type, RECEIVED_AT.plus(FUTURE_LIMIT).plusNanos(1), RECEIVED_AT));

        assertThat(rejected.code()).isEqualTo(ErrorCode.TIMESTAMP_TOO_FAR_FUTURE);
        assertThat(rejected.retryable()).isFalse();
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_FUTURE);
        assertThat(rejected.details()).containsExactly(new FieldDetail(FIELD_OCCURRED_AT, MESSAGE_FUTURE));
        assertThat(rejected.getCause()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("exactly seven days back is accepted and one nanosecond more is TIMESTAMP_TOO_OLD")
    void ageBoundary(Class<? extends TrustEventDto> type) {
        check(type, RECEIVED_AT.minus(AGE_LIMIT), RECEIVED_AT);

        InvalidIngestionRequestException rejected =
                rejection(() -> check(type, RECEIVED_AT.minus(AGE_LIMIT).minusNanos(1), RECEIVED_AT));

        assertThat(rejected.code()).isEqualTo(ErrorCode.TIMESTAMP_TOO_OLD);
        assertThat(rejected.retryable()).isFalse();
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_OLD);
        assertThat(rejected.details()).containsExactly(new FieldDetail(FIELD_OCCURRED_AT, MESSAGE_OLD));
    }

    @Test
    @DisplayName("a far past and a far future timestamp are rejected")
    void farTimestampsAreRejected() {
        Class<? extends TrustEventDto> type = EventDtoFixture.TYPES.get(0);

        assertThat(rejection(() -> check(type, Instant.parse("2000-01-01T00:00:00Z"), RECEIVED_AT)).code())
                .isEqualTo(ErrorCode.TIMESTAMP_TOO_OLD);
        assertThat(rejection(() -> check(type, Instant.parse("2100-01-01T00:00:00Z"), RECEIVED_AT)).code())
                .isEqualTo(ErrorCode.TIMESTAMP_TOO_FAR_FUTURE);
    }

    @Test
    @DisplayName("only the passed receivedAt is used: the same occurredAt passes or fails with it")
    void onlyThePassedReceivedAtMatters() {
        Class<? extends TrustEventDto> type = EventDtoFixture.TYPES.get(0);
        Instant occurredAt = RECEIVED_AT;

        check(type, occurredAt, occurredAt);
        assertThat(rejection(() -> check(type, occurredAt, occurredAt.plus(LATER))).code())
                .isEqualTo(ErrorCode.TIMESTAMP_TOO_OLD);
        assertThat(rejection(() -> check(type, occurredAt, occurredAt.minus(LATER))).code())
                .isEqualTo(ErrorCode.TIMESTAMP_TOO_FAR_FUTURE);
    }

    // ---------- programming errors ----------

    @Test
    @DisplayName("a null argument, or an event missing its environment or occurredAt, is a programming error")
    void nullArgumentsAreProgrammingErrors() {
        TrustEventDto dto = event(EventDtoFixture.TYPES.get(0), Environment.PRODUCTION, RECEIVED_AT);
        TenantContext tenant = tenant(Environment.PRODUCTION);
        TrustEventDto noEnvironment = event(EventDtoFixture.TYPES.get(0), null, RECEIVED_AT);
        TrustEventDto noTimestamp = event(EventDtoFixture.TYPES.get(0), Environment.PRODUCTION, null);

        assertThatThrownBy(() -> GUARD.check(null, tenant, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("dto");
        assertThatThrownBy(() -> GUARD.check(dto, null, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("tenant");
        assertThatThrownBy(() -> GUARD.check(dto, tenant, null))
                .isInstanceOf(NullPointerException.class).hasMessage("receivedAt");
        assertThatThrownBy(() -> GUARD.check(noEnvironment, tenant, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("environment");
        assertThatThrownBy(() -> GUARD.check(noTimestamp, tenant, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("occurredAt");
    }
}