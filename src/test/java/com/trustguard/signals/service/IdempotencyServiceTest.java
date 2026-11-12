package com.trustguard.signals.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.signals.constants.IdempotencyConstants;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {

    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final EventId EVENT = new EventId("01900000-0000-7000-8000-000000000001");
    private static final String KEY_TEXT = "client-key-text-9f3";
    private static final String EXPECTED_REDIS_KEY = TENANT.value() + ":idempotency:" + KEY_TEXT;
    private static final Duration TTL = Duration.ofHours(1);
    private static final Instant RECEIVED = Instant.parse("2026-03-10T12:00:00.123456Z");
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private IdempotencyService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    static Stream<DataAccessException> redisFailures() {
        return Stream.of(
                new RedisConnectionFailureException("host redis-secret:6379"),
                new QueryTimeoutException("redis-secret timed out"),
                new RedisSystemException("redis-secret", new IllegalStateException("redis-secret")));
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new IdempotencyService(redis);
        logger = (Logger) LoggerFactory.getLogger(IdempotencyService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    // ---------- find ----------

    @Test
    @DisplayName("find reads the tenant-prefixed key and returns the stored event id")
    void findReadsTheTenantPrefixedKey() {
        when(values.get(EXPECTED_REDIS_KEY)).thenReturn(EVENT.value());

        assertThat(service.find(TENANT, KEY_TEXT)).contains(EVENT);
    }

    @Test
    @DisplayName("a missing key is a miss")
    void aMissingKeyIsAMiss() {
        when(values.get(EXPECTED_REDIS_KEY)).thenReturn(null);

        assertThat(service.find(TENANT, KEY_TEXT)).isEmpty();
    }

    @Test
    @DisplayName("a stored value that is not an event id is a miss")
    void aGarbageValueIsAMiss() {
        when(values.get(EXPECTED_REDIS_KEY)).thenReturn("not-an-event-id");

        assertThat(service.find(TENANT, KEY_TEXT)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    @DisplayName("a Redis failure on read is a miss and logs only the tenant and the exception class name")
    void aReadFailureIsAMissAndLogsSafely(DataAccessException failure) {
        when(values.get(EXPECTED_REDIS_KEY)).thenThrow(failure);

        Optional<EventId> found = service.find(TENANT, KEY_TEXT);

        assertThat(found).isEmpty();
        assertSafeWarning(failure);
    }

    // ---------- remember ----------

    @Test
    @DisplayName("remember writes the event id with SET NX and exactly the given ttl")
    void rememberUsesSetIfAbsentWithTheGivenTtl() {
        service.remember(TENANT, KEY_TEXT, EVENT, TTL);

        verify(values).setIfAbsent(EXPECTED_REDIS_KEY, EVENT.value(), TTL);
    }

    @Test
    @DisplayName("a zero or negative ttl skips Redis entirely and logs nothing")
    void aNonPositiveTtlSkipsRedis() {
        service.remember(TENANT, KEY_TEXT, EVENT, Duration.ZERO);
        service.remember(TENANT, KEY_TEXT, EVENT, Duration.ofSeconds(-1));

        verifyNoInteractions(values);
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("redisFailures")
    @DisplayName("a Redis failure on write is skipped and logs only the tenant and the exception class name")
    void aWriteFailureIsSkippedAndLogsSafely(DataAccessException failure) {
        when(values.setIfAbsent(EXPECTED_REDIS_KEY, EVENT.value(), TTL)).thenThrow(failure);

        service.remember(TENANT, KEY_TEXT, EVENT, TTL);

        assertSafeWarning(failure);
    }

    // ---------- remainingTtl ----------

    @Test
    @DisplayName("remainingTtl is 24 hours when now equals receivedAt")
    void remainingTtlIsFullWhenNowEqualsReceivedAt() {
        assertThat(IdempotencyService.remainingTtl(RECEIVED, RECEIVED)).isEqualTo(IdempotencyConstants.KEY_TTL);
    }

    @Test
    @DisplayName("remainingTtl shrinks by the time that has passed")
    void remainingTtlShrinksWithTime() {
        assertThat(IdempotencyService.remainingTtl(RECEIVED, RECEIVED.plus(TEN_SECONDS)))
                .isEqualTo(IdempotencyConstants.KEY_TTL.minus(TEN_SECONDS));
    }

    @Test
    @DisplayName("remainingTtl is exactly zero at the expiry instant and negative after it")
    void remainingTtlIsZeroThenNegative() {
        Instant expiry = RECEIVED.plus(IdempotencyConstants.KEY_TTL);

        assertThat(IdempotencyService.remainingTtl(RECEIVED, expiry)).isEqualTo(Duration.ZERO);
        assertThat(IdempotencyService.remainingTtl(RECEIVED, expiry.plusSeconds(1))).isEqualTo(Duration.ofSeconds(-1));
    }

    @Test
    @DisplayName("remainingTtl truncates receivedAt to microseconds, like the stored expires_at")
    void remainingTtlTruncatesToMicroseconds() {
        Instant withNanos = RECEIVED.plusNanos(789);

        assertThat(IdempotencyService.remainingTtl(withNanos, RECEIVED)).isEqualTo(IdempotencyConstants.KEY_TTL);
    }

    private void assertSafeWarning(DataAccessException failure) {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains(TENANT.value())
                .contains(failure.getClass().getSimpleName())
                .doesNotContain(KEY_TEXT)
                .doesNotContain("redis-secret");
        assertThat(event.getThrowableProxy()).isNull();
    }
}