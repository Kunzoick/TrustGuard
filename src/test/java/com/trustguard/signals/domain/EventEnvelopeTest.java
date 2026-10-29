package com.trustguard.signals.domain;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidEventPayloadException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeTest {

    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId PROJECT = new ProjectId("223e4567-e89b-12d3-a456-426614174000");
    private static final EventId EVENT = new EventId("323e4567-e89b-12d3-a456-426614174000");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-02-03T04:05:06Z");

    private static final List<String> COMPONENTS = List.of("eventId", "tenantId", "projectId", "actorType",
            "actorId", "sessionId", "eventType", "environment", "occurredAt", "receivedAt", "sdkVersion",
            "ipAddress", "userAgent", "deviceFingerprint", "idempotencyKey", "payload");

    private record Required(String name, Consumer<EventEnvelope.EventEnvelopeBuilder> clear) {
    }

    // ---------- helpers ----------

    private static Map<String, Object> entry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return (List<Object>) value;
    }

    private static EventEnvelope.EventEnvelopeBuilder valid() {
        return EventEnvelope.builder()
                .eventId(EVENT)
                .tenantId(TENANT)
                .projectId(PROJECT)
                .actorType(ActorType.USER)
                .actorId(new ActorId("user-42"))
                .sessionId("session-1")
                .eventType(EventType.LOGIN)
                .environment(Environment.PRODUCTION)
                .occurredAt(OCCURRED_AT)
                .receivedAt(RECEIVED_AT)
                .sdkVersion("1.0.0")
                .ipAddress("203.0.113.7")
                .userAgent("Mozilla/5.0")
                .deviceFingerprint("fp-1")
                .idempotencyKey("idem-1")
                .payload(entry("k", "v"));
    }

    // ---------- IngestionContext ----------

    @Test
    @DisplayName("an ingestion context holds its four components")
    void contextHoldsItsComponents() {
        IngestionContext context = new IngestionContext(TENANT, PROJECT, EVENT, RECEIVED_AT);

        assertThat(context.tenantId()).isEqualTo(TENANT);
        assertThat(context.projectId()).isEqualTo(PROJECT);
        assertThat(context.eventId()).isEqualTo(EVENT);
        assertThat(context.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    @DisplayName("every null component of an ingestion context is rejected by name")
    void contextRejectsEachNullComponent() {
        assertThatThrownBy(() -> new IngestionContext(null, PROJECT, EVENT, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("tenantId");
        assertThatThrownBy(() -> new IngestionContext(TENANT, null, EVENT, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("projectId");
        assertThatThrownBy(() -> new IngestionContext(TENANT, PROJECT, null, RECEIVED_AT))
                .isInstanceOf(NullPointerException.class).hasMessage("eventId");
        assertThatThrownBy(() -> new IngestionContext(TENANT, PROJECT, EVENT, null))
                .isInstanceOf(NullPointerException.class).hasMessage("receivedAt");
    }

    // ---------- EventEnvelope ----------

    @Test
    @DisplayName("the envelope has exactly the 16 components of acceptance criterion 1, in order")
    void envelopeHasTheSixteenComponents() {
        assertThat(Arrays.stream(EventEnvelope.class.getRecordComponents()).map(RecordComponent::getName).toList())
                .isEqualTo(COMPONENTS).hasSize(16);
    }

    @Test
    @DisplayName("a built envelope holds every component")
    void aBuiltEnvelopeHoldsEveryComponent() {
        EventEnvelope envelope = valid().build();

        assertThat(envelope.eventId()).isEqualTo(EVENT);
        assertThat(envelope.tenantId()).isEqualTo(TENANT);
        assertThat(envelope.projectId()).isEqualTo(PROJECT);
        assertThat(envelope.actorType()).isEqualTo(ActorType.USER);
        assertThat(envelope.actorId()).isEqualTo(new ActorId("user-42"));
        assertThat(envelope.sessionId()).isEqualTo("session-1");
        assertThat(envelope.eventType()).isEqualTo(EventType.LOGIN);
        assertThat(envelope.environment()).isEqualTo(Environment.PRODUCTION);
        assertThat(envelope.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(envelope.receivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(envelope.sdkVersion()).isEqualTo("1.0.0");
        assertThat(envelope.ipAddress()).isEqualTo("203.0.113.7");
        assertThat(envelope.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(envelope.deviceFingerprint()).isEqualTo("fp-1");
        assertThat(envelope.idempotencyKey()).isEqualTo("idem-1");
        assertThat(envelope.payload()).containsOnly(Map.entry("k", "v"));
    }

    @Test
    @DisplayName("every required component is rejected by name when null")
    void everyRequiredComponentIsRejectedWhenNull() {
        List<Required> required = List.of(
                new Required("eventId", builder -> builder.eventId(null)),
                new Required("tenantId", builder -> builder.tenantId(null)),
                new Required("projectId", builder -> builder.projectId(null)),
                new Required("actorType", builder -> builder.actorType(null)),
                new Required("actorId", builder -> builder.actorId(null)),
                new Required("eventType", builder -> builder.eventType(null)),
                new Required("environment", builder -> builder.environment(null)),
                new Required("occurredAt", builder -> builder.occurredAt(null)),
                new Required("receivedAt", builder -> builder.receivedAt(null)),
                new Required("sdkVersion", builder -> builder.sdkVersion(null)),
                new Required("ipAddress", builder -> builder.ipAddress(null)),
                new Required("idempotencyKey", builder -> builder.idempotencyKey(null)));

        for (Required item : required) {
            EventEnvelope.EventEnvelopeBuilder builder = valid();
            item.clear().accept(builder);

            assertThatThrownBy(builder::build).as(item.name())
                    .isInstanceOf(NullPointerException.class).hasMessage(item.name());
        }
    }

    @Test
    @DisplayName("sessionId, userAgent and deviceFingerprint may be null")
    void optionalComponentsMayBeNull() {
        EventEnvelope envelope = valid().sessionId(null).userAgent(null).deviceFingerprint(null).build();

        assertThat(envelope.sessionId()).isNull();
        assertThat(envelope.userAgent()).isNull();
        assertThat(envelope.deviceFingerprint()).isNull();
    }

    @Test
    @DisplayName("a null payload becomes an unmodifiable empty map (Ruling 57)")
    void aNullPayloadBecomesAnEmptyMap() {
        EventEnvelope envelope = valid().payload(null).build();

        assertThat(envelope.payload()).isEmpty();
        assertThatThrownBy(() -> envelope.payload().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the payload is a deep unmodifiable snapshot that the original cannot change (Ruling 69)")
    void thePayloadIsADeepSnapshot() {
        Map<String, Object> inner = entry("a", 1);
        List<Object> list = new ArrayList<>(Arrays.asList("x", null));
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("m", inner);
        original.put("l", list);

        EventEnvelope envelope = valid().payload(original).build();
        original.put("added", 1);
        inner.put("added", 2);
        list.add("y");

        assertThat(envelope.payload()).containsOnlyKeys("m", "l").isNotSameAs(original);
        assertThat(asMap(envelope.payload().get("m"))).containsOnlyKeys("a");
        assertThat(asList(envelope.payload().get("l"))).containsExactly("x", null);
        assertThatThrownBy(() -> envelope.payload().put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asMap(envelope.payload().get("m")).put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> asList(envelope.payload().get("l")).add("z"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("an unsupported payload cannot be put in an envelope by any construction path")
    void anUnsupportedPayloadIsRejected() {
        assertThatThrownBy(() -> valid().payload(entry("k", new Object())).build())
                .isInstanceOf(InvalidEventPayloadException.class);
    }

    @Test
    @DisplayName("envelopes with the same content are equal")
    void envelopesWithTheSameContentAreEqual() {
        assertThat(valid().build()).isEqualTo(valid().build()).hasSameHashCodeAs(valid().build());
        assertThat(valid().build()).isNotEqualTo(valid().ipAddress("203.0.113.8").build());
    }

    @Test
    @DisplayName("toString prints ids, kinds and timestamps only (Rule 14.7)")
    void toStringIsRedacted() {
        EventEnvelope envelope = valid()
                .actorId(new ActorId("actor-secret"))
                .sessionId("session-secret")
                .sdkVersion("9.9.9-secret")
                .ipAddress("198.51.100.77")
                .userAgent("agent-secret")
                .deviceFingerprint("fingerprint-secret")
                .idempotencyKey("idem-secret")
                .payload(entry("token", "payload-secret"))
                .build();

        assertThat(envelope.toString())
                .contains(EVENT.value(), TENANT.value(), PROJECT.value(), "LOGIN", "USER", "PRODUCTION")
                .contains(OCCURRED_AT.toString(), RECEIVED_AT.toString())
                .doesNotContain("actor-secret", "session-secret", "9.9.9-secret", "198.51.100.77",
                        "agent-secret", "fingerprint-secret", "idem-secret", "payload-secret", "token");
    }
}