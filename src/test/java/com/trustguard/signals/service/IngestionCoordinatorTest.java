package com.trustguard.signals.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.EventPersistenceException;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.signals.constants.IdempotencyConstants;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.IngestionContext;
import com.trustguard.signals.dto.LoginEventDto;
import com.trustguard.signals.mapper.EventEnvelopeMapper;
import com.trustguard.signals.parser.IngestionRequestParser;
import com.trustguard.signals.util.EventIdGenerator;
import com.trustguard.signals.validation.IngestionRequestGuard;
import com.trustguard.tenant.context.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ordering and the commit/Redis boundary of the coordinator (Rulings 112, 121 and 125). Everything around it is a
 * mock; the tenant context is a mock too, because a test in a service package must not construct one (Ruling 115).
 * The clock returns a scripted sequence: the first reading is receivedAt, the second is the time after commit.
 */
class IngestionCoordinatorTest {

    private static final TenantId TENANT_ID = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId KEY_PROJECT = new ProjectId("223e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId OTHER_PROJECT = new ProjectId("423e4567-e89b-12d3-a456-426614174000");
    private static final EventId NEW_EVENT = new EventId("01900000-0000-7000-8000-000000000001");
    private static final EventId ORIGINAL = new EventId("01900000-0000-7000-8000-000000000002");
    private static final Instant RECEIVED_AT = Instant.parse("2026-03-10T12:00:00.123456Z");
    private static final Duration ELAPSED = Duration.ofSeconds(10);
    private static final String IDEMPOTENCY_KEY = "idem-1";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    private IngestionRequestParser parser;
    private IngestionRequestGuard guard;
    private EventEnvelopeMapper mapper;
    private EventIdGenerator eventIds;
    private IdempotencyService cache;
    private IngestionService ingestion;
    private TenantContext tenant;

    /** A clock that returns the given readings in order and then repeats the last one. */
    private static final class ScriptedClock extends Clock {
        private final Deque<Instant> readings = new ArrayDeque<>();
        private Instant last;

        ScriptedClock(Instant... instants) {
            for (Instant instant : instants) {
                readings.add(instant);
            }
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (!readings.isEmpty()) {
                last = readings.poll();
            }
            return last;
        }
    }

    @BeforeEach
    void setUp() {
        parser = mock(IngestionRequestParser.class);
        guard = mock(IngestionRequestGuard.class);
        mapper = mock(EventEnvelopeMapper.class);
        eventIds = mock(EventIdGenerator.class);
        cache = mock(IdempotencyService.class);
        ingestion = mock(IngestionService.class);
        tenant = mock(TenantContext.class);
        when(tenant.tenantId()).thenReturn(TENANT_ID);
        when(tenant.projectId()).thenReturn(KEY_PROJECT);
        when(eventIds.generate()).thenReturn(NEW_EVENT);
        when(cache.find(any(), any())).thenReturn(Optional.empty());
    }

    private IngestionCoordinator coordinator(Clock clock) {
        return new IngestionCoordinator(parser, guard, mapper, eventIds, clock, cache, ingestion);
    }

    private static LoginEventDto dto(String projectId) {
        return new LoginEventDto(ActorType.USER, "user-42", null, RECEIVED_AT.minusSeconds(60), "1.0.0",
                Environment.PRODUCTION, "203.0.113.7", null, null, null, null, projectId);
    }

    private static EventEnvelope envelope() {
        return EventEnvelope.builder().eventId(NEW_EVENT).tenantId(TENANT_ID).projectId(KEY_PROJECT)
                .actorType(ActorType.USER).actorId(new ActorId("user-42")).eventType(EventType.LOGIN)
                .environment(Environment.PRODUCTION).occurredAt(RECEIVED_AT.minusSeconds(60))
                .receivedAt(RECEIVED_AT).sdkVersion("1.0.0").ipAddress("203.0.113.7")
                .idempotencyKey(IDEMPOTENCY_KEY).payload(Map.of()).build();
    }

    private LoginEventDto stubParsedBody(String projectId) {
        LoginEventDto dto = dto(projectId);
        when(parser.parse(BODY, LoginEventDto.class)).thenReturn(dto);
        when(mapper.map(eq(dto), any(IngestionContext.class))).thenReturn(envelope());
        return dto;
    }

    @Test
    @DisplayName("a key found in Redis is a Duplicate and accept is never called")
    void aRedisHitIsADuplicateWithoutAccept() {
        stubParsedBody(null);
        when(cache.find(TENANT_ID, IDEMPOTENCY_KEY)).thenReturn(Optional.of(ORIGINAL));

        IngestionResult result = coordinator(new ScriptedClock(RECEIVED_AT)).acceptLogin(BODY, tenant);

        assertThat(result).isEqualTo(new IngestionResult.Duplicate(ORIGINAL));
        verify(ingestion, never()).accept(any());
        verify(cache, never()).remember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a Duplicate from PostgreSQL is returned and Redis is not written")
    void aPostgresDuplicateIsNotWrittenBack() {
        stubParsedBody(null);
        when(ingestion.accept(any())).thenReturn(new IngestionResult.Duplicate(ORIGINAL));

        IngestionResult result = coordinator(new ScriptedClock(RECEIVED_AT)).acceptLogin(BODY, tenant);

        assertThat(result).isEqualTo(new IngestionResult.Duplicate(ORIGINAL));
        verify(cache, never()).remember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an Accepted result is remembered once, with 24 hours minus the time elapsed since receipt")
    void anAcceptedEventIsRememberedWithTheRemainingTtl() {
        stubParsedBody(null);
        IngestionResult.Accepted stored = new IngestionResult.Accepted(NEW_EVENT, RECEIVED_AT);
        when(ingestion.accept(any())).thenReturn(stored);

        IngestionResult result = coordinator(new ScriptedClock(RECEIVED_AT, RECEIVED_AT.plus(ELAPSED)))
                .acceptLogin(BODY, tenant);

        assertThat(result).isEqualTo(stored);
        verify(cache).remember(TENANT_ID, IDEMPOTENCY_KEY, NEW_EVENT, IdempotencyConstants.KEY_TTL.minus(ELAPSED));
    }

    @Test
    @DisplayName("when accept throws, Redis is never written")
    void aFailedAcceptWritesNothingToRedis() {
        stubParsedBody(null);
        when(ingestion.accept(any())).thenThrow(new EventPersistenceException("fixed", false));

        assertThatThrownBy(() -> coordinator(new ScriptedClock(RECEIVED_AT)).acceptLogin(BODY, tenant))
                .isInstanceOf(EventPersistenceException.class);

        verify(cache, never()).remember(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a body projectId of another project is rejected and nothing after the guard is called")
    void anotherProjectIsRejected() {
        stubParsedBody(OTHER_PROJECT.value());

        assertThatThrownBy(() -> coordinator(new ScriptedClock(RECEIVED_AT)).acceptLogin(BODY, tenant))
                .isInstanceOfSatisfying(InvalidIngestionRequestException.class, thrown -> {
                    assertThat(thrown.details()).hasSize(1);
                    assertThat(thrown.details().get(0).field()).isEqualTo("projectId");
                });

        verifyNoInteractions(mapper, eventIds, cache, ingestion);
    }

    @Test
    @DisplayName("an absent projectId means the key's project, and the context carries the receive time and event id")
    void anAbsentProjectDefaultsToTheKeysProject() {
        LoginEventDto dto = stubParsedBody(null);
        when(ingestion.accept(any())).thenReturn(new IngestionResult.Accepted(NEW_EVENT, RECEIVED_AT));

        coordinator(new ScriptedClock(RECEIVED_AT, RECEIVED_AT)).acceptLogin(BODY, tenant);

        ArgumentCaptor<IngestionContext> captor = ArgumentCaptor.forClass(IngestionContext.class);
        verify(mapper).map(eq(dto), captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo(KEY_PROJECT);
        assertThat(captor.getValue().tenantId()).isEqualTo(TENANT_ID);
        assertThat(captor.getValue().eventId()).isEqualTo(NEW_EVENT);
        assertThat(captor.getValue().receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    @DisplayName("the key's own project in the body is accepted")
    void theKeysOwnProjectIsAccepted() {
        stubParsedBody(KEY_PROJECT.value());
        when(ingestion.accept(any())).thenReturn(new IngestionResult.Accepted(NEW_EVENT, RECEIVED_AT));

        IngestionResult result = coordinator(new ScriptedClock(RECEIVED_AT, RECEIVED_AT)).acceptLogin(BODY, tenant);

        assertThat(result).isInstanceOf(IngestionResult.Accepted.class);
    }

    @Test
    @DisplayName("when no lifetime is left after the commit, the Redis write is skipped")
    @SuppressWarnings("unchecked")
    void anExpiredRemainderSkipsTheRedisWrite() {
        stubParsedBody(null);
        when(ingestion.accept(any())).thenReturn(new IngestionResult.Accepted(NEW_EVENT, RECEIVED_AT));
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        Instant atExpiry = RECEIVED_AT.plus(IdempotencyConstants.KEY_TTL);
        IngestionCoordinator withRealCache = new IngestionCoordinator(parser, guard, mapper, eventIds,
                new ScriptedClock(RECEIVED_AT, atExpiry), new IdempotencyService(redis), ingestion);

        IngestionResult result = withRealCache.acceptLogin(BODY, tenant);

        assertThat(result).isInstanceOf(IngestionResult.Accepted.class);
        verify(values, never()).setIfAbsent(any(), any(), any(Duration.class));
    }
}