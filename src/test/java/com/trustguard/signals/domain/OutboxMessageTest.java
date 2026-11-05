package com.trustguard.signals.domain;

import java.time.Instant;
import java.util.ArrayList;
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

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessageTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final EventId EVENT = new EventId("323e4567-e89b-12d3-a456-426614174000");
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId PROJECT = new ProjectId("223e4567-e89b-12d3-a456-426614174000");
    private static final Instant OCCURRED_AT = Instant.parse("2026-03-10T11:59:00.5Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-03-10T12:00:00.123456789Z");

    private static final List<String> EXPECTED_KEYS = List.of("actorId", "actorType", "environment", "eventId",
            "eventType", "occurredAt", "projectId", "receivedAt", "schemaVersion", "tenantId");
    private static final List<String> EXCLUDED_KEYS = List.of("ipAddress", "userAgent", "sessionId",
            "deviceFingerprint", "idempotencyKey", "payload", "sdkVersion");
    private static final List<String> SECRET_VALUES = List.of("198.51.100.77", "agent-secret", "session-secret",
            "fingerprint-secret", "idem-secret", "9.9.9-secret", "payload-secret");

    private record Required(String name, Consumer<OutboxMessage.OutboxMessageBuilder> clear) {
    }

    private static EventEnvelope envelope() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", "payload-secret");
        return EventEnvelope.builder()
                .eventId(EVENT).tenantId(TENANT).projectId(PROJECT)
                .actorType(ActorType.USER).actorId(new ActorId("user-42")).sessionId("session-secret")
                .eventType(EventType.LOGIN).environment(Environment.PRODUCTION)
                .occurredAt(OCCURRED_AT).receivedAt(RECEIVED_AT)
                .sdkVersion("9.9.9-secret").ipAddress("198.51.100.77").userAgent("agent-secret")
                .deviceFingerprint("fingerprint-secret").idempotencyKey("idem-secret").payload(payload)
                .build();
    }

    private static OutboxMessage.OutboxMessageBuilder validBuilder() {
        return OutboxMessage.builder()
                .eventId(EVENT).tenantId(TENANT).projectId(PROJECT)
                .eventType(EventType.LOGIN).actorType(ActorType.USER).actorId(new ActorId("user-42"))
                .environment(Environment.PRODUCTION).occurredAt(OCCURRED_AT).receivedAt(RECEIVED_AT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) {
        return (Map<String, Object>) MAPPER.readValue(json, Object.class);
    }

    @Test
    @DisplayName("the message has exactly the ten fixed keys, sorted, with schemaVersion 1")
    void theMessageHasExactlyTheFixedKeys() {
        String json = OutboxMessage.from(envelope()).toJson();
        Map<String, Object> fields = parse(json);

        assertThat(new ArrayList<>(fields.keySet())).containsExactlyElementsOf(EXPECTED_KEYS);
        assertThat(fields.get("schemaVersion")).isEqualTo(1);
        assertThat(OutboxMessage.SCHEMA_VERSION).isEqualTo(1);
    }

    @Test
    @DisplayName("every excluded field is absent, as keys and as values")
    void everyExcludedFieldIsAbsent() {
        String json = OutboxMessage.from(envelope()).toJson();

        assertThat(parse(json).keySet()).doesNotContainAnyElementsOf(EXCLUDED_KEYS);
        for (String secret : SECRET_VALUES) {
            assertThat(json).as(secret).doesNotContain(secret);
        }
    }

    @Test
    @DisplayName("the values are the envelope's own ids, kinds and the actor id")
    void theValuesAreTheEnvelopesOwn() {
        Map<String, Object> fields = parse(OutboxMessage.from(envelope()).toJson());

        assertThat(fields).containsEntry("eventId", EVENT.value()).containsEntry("tenantId", TENANT.value())
                .containsEntry("projectId", PROJECT.value()).containsEntry("eventType", "LOGIN")
                .containsEntry("actorType", "USER").containsEntry("actorId", "user-42")
                .containsEntry("environment", "PRODUCTION");
    }

    @Test
    @DisplayName("both timestamps are truncated to microseconds")
    void timestampsAreTruncatedToMicroseconds() {
        Map<String, Object> fields = parse(OutboxMessage.from(envelope()).toJson());

        assertThat(fields).containsEntry("receivedAt", "2026-03-10T12:00:00.123456Z")
                .containsEntry("occurredAt", "2026-03-10T11:59:00.500Z");
    }

    @Test
    @DisplayName("the JSON is canonical: compact, deterministic and equal for equal messages")
    void theJsonIsCanonical() {
        String first = OutboxMessage.from(envelope()).toJson();
        String second = OutboxMessage.from(envelope()).toJson();

        assertThat(first).isEqualTo(second).doesNotContain(" ").doesNotContain("\n");
        assertThat(first).startsWith("{\"actorId\":\"user-42\",\"actorType\":\"USER\"");
    }

    @Test
    @DisplayName("a missing envelope, or any missing component built through the builder, is a programming error")
    void nullsAreRejected() {
        assertThatThrownBy(() -> OutboxMessage.from(null)).isInstanceOf(NullPointerException.class)
                .hasMessage("envelope");

        List<Required> required = List.of(
                new Required("eventId", builder -> builder.eventId(null)),
                new Required("tenantId", builder -> builder.tenantId(null)),
                new Required("projectId", builder -> builder.projectId(null)),
                new Required("eventType", builder -> builder.eventType(null)),
                new Required("actorType", builder -> builder.actorType(null)),
                new Required("actorId", builder -> builder.actorId(null)),
                new Required("environment", builder -> builder.environment(null)),
                new Required("occurredAt", builder -> builder.occurredAt(null)),
                new Required("receivedAt", builder -> builder.receivedAt(null)));
        for (Required item : required) {
            OutboxMessage.OutboxMessageBuilder builder = validBuilder();
            item.clear().accept(builder);

            assertThatThrownBy(builder::build).as(item.name())
                    .isInstanceOf(NullPointerException.class).hasMessage(item.name());
        }
    }

    @Test
    @DisplayName("the builder builds the same message as from() when given already-truncated timestamps")
    void theBuilderAgreesWithFrom() {
        OutboxMessage viaFrom = OutboxMessage.from(envelope());
        OutboxMessage viaBuilder = validBuilder()
                .occurredAt(viaFrom.occurredAt()).receivedAt(viaFrom.receivedAt()).build();

        assertThat(viaBuilder).isEqualTo(viaFrom);
        assertThat(viaBuilder.toJson()).isEqualTo(viaFrom.toJson());
    }
}