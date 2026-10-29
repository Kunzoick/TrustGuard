package com.trustguard.signals.mapper;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.SessionIdRequiredException;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.IngestionContext;
import com.trustguard.signals.dto.ContentSubmitEventDto;
import com.trustguard.signals.dto.EventDtoFixture;
import com.trustguard.signals.dto.LoginEventDto;
import com.trustguard.signals.dto.TransactionEventDto;
import com.trustguard.signals.dto.TrustEventDto;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeMapperTest {

    private static final int KEY_BYTES = 32;
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId CONTEXT_PROJECT = new ProjectId("223e4567-e89b-12d3-a456-426614174000");
    private static final EventId EVENT_ID = new EventId("323e4567-e89b-12d3-a456-426614174000");
    private static final Instant RECEIVED_AT = Instant.parse("2026-02-03T04:05:06.789Z");
    private static final Instant VECTOR_OCCURRED_AT = Instant.parse("2026-01-02T03:04:05.123Z");
    private static final String SECRET_KEY = "secret-key";

    /** From b009_vectors.py (ANON_ASCII), pinned in B-009. Master key = bytes 0x00..0x1f. */
    private static final String ANON_VECTOR =
            "anon_d8a4dfe60b753f7d92b8f59ced12351da2298952020c9b7baf18fa5ed9e68273";

    /** From b009_vectors.py (IDEM_WITH_PAYLOAD): LOGIN, user-42, payload {"a":1,"b":[true,null],"c":"x"}. */
    private static final String IDEM_WITH_PAYLOAD_VECTOR =
            "c7716e660b9c5d2c6ce14828646fdbb4e4433cd77e2b7a9b2e27b5c304120220";

    /** From b009_vectors.py (IDEM_EMPTY_PAYLOAD): LOGIN, user-42, empty payload. */
    private static final String IDEM_EMPTY_PAYLOAD_VECTOR =
            "34037566a7943835d4ecba0b33b76e1e5065d779c03092c3baa05c7a7d27f9f0";

    /**
     * A payload whose entry set changes between reads: the first call returns {"a":1}, every later
     * call returns {"a":2}. A mapper that reads it twice would store one value and hash another.
     */
    private static final class FlippingMap extends AbstractMap<String, Object> {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Set<Map.Entry<String, Object>> entrySet() {
            int value = calls.incrementAndGet() == 1 ? 1 : 2;
            return Set.of(Map.<String, Object>entry("a", value));
        }
    }

    // ---------- sources and helpers ----------

    static Stream<Class<? extends TrustEventDto>> types() {
        return EventDtoFixture.TYPES.stream();
    }

    static Stream<Arguments> typeAndEventType() {
        return Stream.of(
                Arguments.of(LoginEventDto.class, EventType.LOGIN),
                Arguments.of(TransactionEventDto.class, EventType.TRANSACTION),
                Arguments.of(ContentSubmitEventDto.class, EventType.CONTENT_SUBMIT));
    }

    private static byte[] key() {
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    private static EventEnvelopeMapper mapper() {
        return new EventEnvelopeMapper(new AnonymousActorIdGenerator(key()), new IdempotencyKeyGenerator(key()));
    }

    private static IngestionContext context() {
        return new IngestionContext(TENANT, CONTEXT_PROJECT, EVENT_ID, RECEIVED_AT);
    }

    private static Map<String, Object> entry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /** Canonical form: {"a":1,"b":[true,null],"c":"x"}; inserted out of order on purpose. */
    private static Map<String, Object> vectorPayload() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("c", "x");
        map.put("a", 1);
        map.put("b", Arrays.asList(true, null));
        return map;
    }

    private static Object nested(int depth) {
        Object value = new ArrayList<Object>();
        for (int level = 1; level < depth; level++) {
            List<Object> outer = new ArrayList<>();
            outer.add(value);
            value = outer;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static EventDtoFixture.Builder anonymous() {
        return EventDtoFixture.builder().actorType(ActorType.ANONYMOUS).actorId(null).sessionId("session-abc");
    }

    private static EventDtoFixture.Builder vectorEvent() {
        return EventDtoFixture.builder().actorType(ActorType.USER).actorId("user-42")
                .occurredAt(VECTOR_OCCURRED_AT).idempotencyKey(null).payload(vectorPayload());
    }

    // ---------- field rules (Rules 6.1 and 6.3, Ruling 71) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeAndEventType")
    @DisplayName("a USER event maps every field")
    void aUserEventMapsEveryField(Class<? extends TrustEventDto> type, EventType expectedEventType) {
        TrustEventDto dto = EventDtoFixture.builder().build(type);

        EventEnvelope envelope = mapper().map(dto, context());

        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.tenantId()).isEqualTo(TENANT);
        assertThat(envelope.projectId()).isEqualTo(CONTEXT_PROJECT);
        assertThat(envelope.actorType()).isEqualTo(ActorType.USER);
        assertThat(envelope.actorId()).isEqualTo(new ActorId("user-42"));
        assertThat(envelope.sessionId()).isEqualTo("session-1");
        assertThat(envelope.eventType()).isEqualTo(expectedEventType);
        assertThat(envelope.environment()).isEqualTo(dto.environment());
        assertThat(envelope.occurredAt()).isEqualTo(EventDtoFixture.OCCURRED_AT);
        assertThat(envelope.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(envelope.sdkVersion()).isEqualTo("1.0.0");
        assertThat(envelope.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(envelope.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(envelope.deviceFingerprint()).isEqualTo("fp-1");
        assertThat(envelope.idempotencyKey()).isEqualTo("idem-1");
        assertThat(envelope.payload()).containsOnly(Map.entry("k", "v"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("tenant, project, event id and receivedAt come only from the context")
    void platformFieldsComeOnlyFromTheContext(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = EventDtoFixture.builder().projectId(EventDtoFixture.PROJECT_ID).build(type);

        EventEnvelope envelope = mapper().map(dto, context());

        assertThat(dto.projectId()).isNotEqualTo(CONTEXT_PROJECT.value());
        assertThat(envelope.projectId()).isEqualTo(CONTEXT_PROJECT).isNotEqualTo(new ProjectId(dto.projectId()));
        assertThat(envelope.receivedAt()).isEqualTo(RECEIVED_AT).isNotEqualTo(dto.occurredAt());
        assertThat(envelope.eventId()).isEqualTo(EVENT_ID);
        assertThat(envelope.tenantId()).isEqualTo(TENANT);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an ANONYMOUS event gets the independent-vector actor id")
    void anAnonymousEventGetsTheVectorActorId(Class<? extends TrustEventDto> type) {
        EventEnvelope envelope = mapper().map(anonymous().build(type), context());

        assertThat(envelope.actorType()).isEqualTo(ActorType.ANONYMOUS);
        assertThat(envelope.actorId().value()).isEqualTo(ANON_VECTOR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an actorId sent with an ANONYMOUS event is ignored")
    void aSuppliedActorIdOnAnAnonymousEventIsIgnored(Class<? extends TrustEventDto> type) {
        EventEnvelope envelope = mapper().map(anonymous().actorId("tenant-supplied").build(type), context());

        assertThat(envelope.actorId().value()).isEqualTo(ANON_VECTOR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a null, empty or blank sessionId on an ANONYMOUS event throws SESSION_ID_REQUIRED")
    void anAnonymousEventWithoutASessionIdIsRejected(Class<? extends TrustEventDto> type) {
        for (String missing : new String[] {null, "", "   "}) {
            TrustEventDto dto = anonymous().sessionId(missing).build(type);

            assertThatThrownBy(() -> mapper().map(dto, context()))
                    .isInstanceOfSatisfying(SessionIdRequiredException.class,
                            thrown -> assertThat(thrown.code()).isEqualTo(ErrorCode.SESSION_ID_REQUIRED));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an ANONYMOUS session id with an unpaired surrogate is rejected without echoing it")
    void anAnonymousSessionIdWithAnUnpairedSurrogateIsRejected(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = anonymous().sessionId("secret-session" + (char) 0xD800).build(type);

        assertThatThrownBy(() -> mapper().map(dto, context()))
                .isInstanceOf(InvalidEventPayloadException.class)
                .hasMessageNotContaining("secret-session");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a missing sessionId is fine for USER and SERVICE")
    void aMissingSessionIdIsFineForUserAndService(Class<? extends TrustEventDto> type) {
        for (ActorType actorType : List.of(ActorType.USER, ActorType.SERVICE)) {
            TrustEventDto dto = EventDtoFixture.builder().actorType(actorType).sessionId(null).build(type);

            assertThat(mapper().map(dto, context()).sessionId()).isNull();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("sessionId is kept for USER and ANONYMOUS and dropped for SERVICE")
    void sessionIdRuleByActorType(Class<? extends TrustEventDto> type) {
        TrustEventDto user = EventDtoFixture.builder().actorType(ActorType.USER).build(type);
        TrustEventDto service = EventDtoFixture.builder().actorType(ActorType.SERVICE).actorId("svc-1").build(type);
        TrustEventDto anon = anonymous().build(type);

        assertThat(mapper().map(user, context()).sessionId()).isEqualTo("session-1");
        assertThat(mapper().map(service, context()).sessionId()).isNull();
        assertThat(mapper().map(anon, context()).sessionId()).isEqualTo("session-abc");
        assertThat(mapper().map(service, context()).actorId()).isEqualTo(new ActorId("svc-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a USER or SERVICE event without an actorId is a precondition violation, not a validation")
    void aUserOrServiceEventWithoutAnActorIdFails(Class<? extends TrustEventDto> type) {
        for (ActorType actorType : List.of(ActorType.USER, ActorType.SERVICE)) {
            TrustEventDto dto = EventDtoFixture.builder().actorType(actorType).actorId(null).build(type);

            assertThatThrownBy(() -> mapper().map(dto, context()))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage(SignalConstants.ACTOR_ID_REQUIRED_MESSAGE);
        }
    }

    @Test
    @DisplayName("the mapper does not validate formats: a blank actorId only fails in the ActorId record")
    void aBlankActorIdOnAnUnvalidatedDtoIsAContractViolation() {
        TrustEventDto dto = EventDtoFixture.builder().actorId("   ").build(LoginEventDto.class);

        assertThatThrownBy(() -> mapper().map(dto, context())).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- idempotency key (Rule 6.4, Rulings 71 and 74) ----------

    @Test
    @DisplayName("the fallback key for a LOGIN event equals the independent vector, for a null, empty or blank key")
    void theFallbackKeyEqualsTheVectorForALoginEvent() {
        for (String absent : new String[] {null, "", "   "}) {
            TrustEventDto dto = vectorEvent().idempotencyKey(absent).build(LoginEventDto.class);

            assertThat(mapper().map(dto, context()).idempotencyKey()).as("key '" + absent + "'")
                    .isEqualTo(IDEM_WITH_PAYLOAD_VECTOR);
        }
    }

    @Test
    @DisplayName("a null or empty payload gives the independent empty-payload vector")
    void aNullOrEmptyPayloadGivesTheEmptyPayloadVector() {
        for (Map<String, Object> empty : Arrays.asList(null, new LinkedHashMap<String, Object>())) {
            TrustEventDto dto = vectorEvent().payload(empty).build(LoginEventDto.class);

            assertThat(mapper().map(dto, context()).idempotencyKey()).isEqualTo(IDEM_EMPTY_PAYLOAD_VECTOR);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("the fallback key is the generator's output over the payload the envelope stores")
    void theFallbackKeyIsAFunctionOfTheStoredPayload(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = vectorEvent().build(type);

        EventEnvelope envelope = mapper().map(dto, context());

        String expected = new IdempotencyKeyGenerator(key()).generate(TENANT, envelope.actorId(),
                envelope.eventType(), envelope.occurredAt(), envelope.payload());
        assertThat(envelope.idempotencyKey()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("the key and the stored payload come from one read of a payload that changes between reads")
    void theKeyAndTheStoredPayloadComeFromOneRead(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = EventDtoFixture.builder().idempotencyKey(null).payload(new FlippingMap()).build(type);

        EventEnvelope envelope = mapper().map(dto, context());

        IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator(key());
        assertThat(envelope.payload()).containsOnly(Map.entry("a", 1));
        assertThat(envelope.idempotencyKey())
                .isEqualTo(generator.generate(TENANT, envelope.actorId(), envelope.eventType(),
                        envelope.occurredAt(), entry("a", 1)))
                .isNotEqualTo(generator.generate(TENANT, envelope.actorId(), envelope.eventType(),
                        envelope.occurredAt(), entry("a", 2)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a non-blank tenant key is kept exactly as sent")
    void aNonBlankTenantKeyIsKeptAsSent(Class<? extends TrustEventDto> type) {
        for (String supplied : List.of("idem-1", " padded-key ", "a".repeat(SignalConstants.MAX_TEXT_LENGTH))) {
            TrustEventDto dto = EventDtoFixture.builder().idempotencyKey(supplied).build(type);

            assertThat(mapper().map(dto, context()).idempotencyKey()).isEqualTo(supplied);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeAndEventType")
    @DisplayName("the fallback key of an ANONYMOUS event uses the generated actor id, not the supplied one")
    void theFallbackKeyUsesTheFinalActorId(Class<? extends TrustEventDto> type, EventType eventType) {
        TrustEventDto dto = anonymous().actorId("tenant-supplied").occurredAt(VECTOR_OCCURRED_AT)
                .idempotencyKey(null).payload(vectorPayload()).build(type);
        IdempotencyKeyGenerator generator = new IdempotencyKeyGenerator(key());

        String actual = mapper().map(dto, context()).idempotencyKey();

        assertThat(actual).isEqualTo(generator.generate(TENANT, new ActorId(ANON_VECTOR), eventType,
                VECTOR_OCCURRED_AT, vectorPayload()));
        assertThat(actual).isNotEqualTo(generator.generate(TENANT, new ActorId("tenant-supplied"), eventType,
                VECTOR_OCCURRED_AT, vectorPayload()));
    }

    @Test
    @DisplayName("the event type is part of the fallback key, so three types give three keys")
    void theEventTypeChangesTheFallbackKey() {
        List<String> keys = EventDtoFixture.TYPES.stream()
                .map(type -> mapper().map(vectorEvent().build(type), context()).idempotencyKey())
                .toList();

        assertThat(keys).doesNotHaveDuplicates().hasSize(3);
    }

    // ---------- parity ----------

    @Test
    @DisplayName("all three DTO types map identically apart from eventType")
    void allThreeTypesMapIdenticallyApartFromTheEventType() {
        EventEnvelope baseline = mapper().map(EventDtoFixture.builder().build(LoginEventDto.class), context());

        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            EventEnvelope envelope = mapper().map(EventDtoFixture.builder().build(type), context());

            assertThat(envelope).as(type.getSimpleName()).usingRecursiveComparison()
                    .ignoringFields("eventType").isEqualTo(baseline);
        }
        assertThat(EventDtoFixture.TYPES.stream()
                .map(type -> mapper().map(EventDtoFixture.builder().build(type), context()).eventType())
                .distinct().count()).isEqualTo(3);
    }

    // ---------- payload snapshot (Ruling 69) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a null payload becomes an unmodifiable empty map")
    void aNullPayloadBecomesAnEmptyMap(Class<? extends TrustEventDto> type) {
        EventEnvelope envelope = mapper().map(EventDtoFixture.builder().payload(null).build(type), context());

        assertThat(envelope.payload()).isEmpty();
        assertThatThrownBy(() -> envelope.payload().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("the envelope payload is a deep unmodifiable snapshot that later changes cannot reach")
    void theEnvelopePayloadIsADeepSnapshot(Class<? extends TrustEventDto> type) {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("inner", "v");
        inner.put("nothing", null);
        List<Object> list = new ArrayList<>(Arrays.asList(1, null, "x"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("m", inner);
        payload.put("l", list);
        payload.put("n", null);
        TrustEventDto dto = EventDtoFixture.builder().payload(payload).build(type);

        EventEnvelope envelope = mapper().map(dto, context());
        payload.put("added", 1);
        inner.put("added", 2);
        list.add(3);

        assertThat(envelope.payload()).isNotSameAs(dto.payload()).containsOnlyKeys("m", "l", "n");
        assertThat(envelope.payload().get("n")).isNull();
        assertThat(asMap(envelope.payload().get("m"))).containsOnlyKeys("inner", "nothing");
        assertThat(asList(envelope.payload().get("l"))).containsExactly(1, null, "x");
        assertThatThrownBy(() -> envelope.payload().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(envelope.payload().get("m")).put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(envelope.payload().get("l")).add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a cyclic payload is rejected with InvalidEventPayloadException, with or without a tenant key")
    void aCyclicPayloadIsRejected(Class<? extends TrustEventDto> type) {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);
        for (String tenantKey : new String[] {"idem-1", null}) {
            TrustEventDto dto = EventDtoFixture.builder().idempotencyKey(tenantKey).payload(cycle).build(type);

            assertThatThrownBy(() -> mapper().map(dto, context()))
                    .isInstanceOf(InvalidEventPayloadException.class);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("32 containers map and 33 are rejected, with or without a tenant key, without echoing keys")
    void theDepthBoundaryHoldsThroughTheMapper(Class<? extends TrustEventDto> type) {
        int allowedInside = SignalConstants.MAX_NESTING_DEPTH - 1;
        for (String tenantKey : new String[] {"idem-1", null}) {
            TrustEventDto fits = EventDtoFixture.builder().idempotencyKey(tenantKey)
                    .payload(entry(SECRET_KEY, nested(allowedInside))).build(type);
            TrustEventDto tooDeep = EventDtoFixture.builder().idempotencyKey(tenantKey)
                    .payload(entry(SECRET_KEY, nested(allowedInside + 1))).build(type);

            assertThat(mapper().map(fits, context()).payload()).containsKey(SECRET_KEY);
            assertThatThrownBy(() -> mapper().map(tooDeep, context()))
                    .isInstanceOf(InvalidEventPayloadException.class)
                    .hasMessageNotContaining(SECRET_KEY);
        }
    }

    // ---------- null arguments (Ruling 72) ----------

    @Test
    @DisplayName("a null dto or context is a programming error")
    void nullArgumentsAreProgrammingErrors() {
        TrustEventDto dto = EventDtoFixture.builder().build(LoginEventDto.class);

        assertThatThrownBy(() -> mapper().map(null, context()))
                .isInstanceOf(NullPointerException.class).hasMessage("dto");
        assertThatThrownBy(() -> mapper().map(dto, null))
                .isInstanceOf(NullPointerException.class).hasMessage("context");
    }

    @Test
    @DisplayName("a null generator is rejected at construction")
    void nullGeneratorsAreRejectedAtConstruction() {
        assertThatThrownBy(() -> new EventEnvelopeMapper(null, new IdempotencyKeyGenerator(key())))
                .isInstanceOf(NullPointerException.class).hasMessage("anonymousActorIdGenerator");
        assertThatThrownBy(() -> new EventEnvelopeMapper(new AnonymousActorIdGenerator(key()), null))
                .isInstanceOf(NullPointerException.class).hasMessage("idempotencyKeyGenerator");
    }
}