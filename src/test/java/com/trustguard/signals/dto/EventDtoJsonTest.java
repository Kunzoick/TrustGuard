package com.trustguard.signals.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.signals.util.CanonicalJsonSerializer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventDtoJsonTest {

    /** Ruling 68: the production mapper is B-010's; this one is configured per Rule 16.5. */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final JsonMapper STRICT_ENUM_MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .build();

    private static final String PLACEHOLDER = "PLACEHOLDER";

    // ---------- sources and helpers ----------

    static Stream<Class<? extends TrustEventDto>> types() {
        return EventDtoFixture.TYPES.stream();
    }

    static Stream<Arguments> typeAndUnknownField() {
        return EventDtoFixture.TYPES.stream().flatMap(type ->
                Stream.of("tenantId", "eventId", "unknownField").map(name -> Arguments.of(type, name)));
    }

    static Stream<Arguments> enumCases() {
        return Stream.of(
                Arguments.of("actorType", ActorType.class, "ROBOT"),
                Arguments.of("environment", Environment.class, "PROD"));
    }

    static Stream<Arguments> isoInstants() {
        Instant expected = Instant.parse("2026-01-02T03:04:05Z");
        return Stream.of(
                Arguments.of("2026-01-02T03:04:05Z", expected),
                Arguments.of("2026-01-02T03:04:05.123456789Z", Instant.parse("2026-01-02T03:04:05.123456789Z")),
                Arguments.of("2026-01-02T05:04:05+02:00", expected));
    }

    private static Map<String, Object> baseBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "USER");
        body.put("actorId", "user-42");
        body.put("sessionId", "session-1");
        body.put("occurredAt", "2026-01-02T03:04:05Z");
        body.put("sdkVersion", "1.0.0");
        body.put("environment", "PRODUCTION");
        body.put("ipAddress", "203.0.113.7");
        body.put("userAgent", "Mozilla/5.0");
        body.put("deviceFingerprint", "fp-1");
        body.put("payload", new LinkedHashMap<>(Map.of("k", "v")));
        body.put("idempotencyKey", "idem-1");
        body.put("projectId", EventDtoFixture.PROJECT_ID);
        return body;
    }

    private static String toJson(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    private static TrustEventDto read(Class<? extends TrustEventDto> type, Map<String, Object> body) {
        return MAPPER.readValue(toJson(body), type);
    }

    private static String bodyWithRawPayload(String rawPayload) {
        Map<String, Object> body = baseBody();
        body.remove("payload");
        String json = toJson(body);
        return json.substring(0, json.length() - 1) + ",\"payload\":" + rawPayload + "}";
    }

    // ---------- deserialization ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a valid body deserializes into the record")
    void aValidBodyDeserializesIntoEachRecord(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = read(type, baseBody());

        assertThat(dto).isInstanceOf(type);
        assertThat(dto.actorType()).isEqualTo(ActorType.USER);
        assertThat(dto.actorId()).isEqualTo("user-42");
        assertThat(dto.occurredAt()).isEqualTo(EventDtoFixture.OCCURRED_AT);
        assertThat(dto.environment()).isEqualTo(Environment.PRODUCTION);
        assertThat(dto.payload()).containsEntry("k", "v");
        assertThat(dto.projectId()).isEqualTo(EventDtoFixture.PROJECT_ID);
        assertThat(EventDtoFixture.validate(dto)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("receivedAt is silently ignored whatever it holds (Rule 6.1)")
    void receivedAtIsIgnoredWhateverItHolds(Class<? extends TrustEventDto> type) {
        TrustEventDto without = read(type, baseBody());
        for (Object value : Arrays.asList("2020-01-01T00:00:00Z", 12345, Map.of("x", 1), null)) {
            Map<String, Object> body = baseBody();
            body.put("receivedAt", value);

            assertThat(read(type, body)).as("receivedAt=" + value).isEqualTo(without);
        }
        assertThat(Arrays.stream(type.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("receivedAt");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("typeAndUnknownField")
    @DisplayName("tenantId, eventId and any other unknown property fail")
    void serverFieldsAndUnknownPropertiesFail(Class<? extends TrustEventDto> type, String name) {
        Map<String, Object> body = baseBody();
        body.put(name, "x");

        assertThatThrownBy(() -> read(type, body))
                .isInstanceOfSatisfying(UnrecognizedPropertyException.class,
                        exception -> assertThat(exception.getPropertyName()).isEqualTo(name));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("eventType in the body is rejected, not silently skipped (Ruling 53, risk D1)")
    void eventTypeInTheBodyIsRejected(Class<? extends TrustEventDto> type) {
        Map<String, Object> body = baseBody();
        body.put("eventType", "LOGIN");

        assertThatThrownBy(() -> read(type, body))
                .isInstanceOfSatisfying(UnrecognizedPropertyException.class,
                        exception -> assertThat(exception.getPropertyName()).isEqualTo("eventType"));
    }

    // ---------- enums (AC 6) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("enumCases")
    @DisplayName("an unknown enum value exposes the field name and the valid values without parsing the message")
    void unknownEnumValueExposesTheFieldAndTheValidValues(String property, Class<?> enumType, String bad) {
        List<String> validValues = Arrays.stream(enumType.getEnumConstants()).map(Object::toString).toList();
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            Map<String, Object> body = baseBody();
            body.put(property, bad);

            assertThatThrownBy(() -> read(type, body))
                    .isInstanceOfSatisfying(InvalidFormatException.class, exception -> {
                        assertThat(exception.getTargetType()).isEqualTo(enumType);
                        assertThat(exception.getPath()).isNotEmpty();
                        assertThat(exception.getPath().get(0).getPropertyName()).isEqualTo(property);
                        assertThat(Arrays.stream(exception.getTargetType().getEnumConstants())
                                .map(Object::toString).toList()).containsExactlyElementsOf(validValues);
                    });
        }
    }

    @Test
    @DisplayName("enum values are case sensitive")
    void lowercaseEnumValueIsRejected() {
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            Map<String, Object> body = baseBody();
            body.put("actorType", "user");

            assertThatThrownBy(() -> read(type, body)).isInstanceOf(InvalidFormatException.class);
        }
    }

    @Test
    @DisplayName("a numeric enum ordinal is rejected when FAIL_ON_NUMBERS_FOR_ENUMS is on (B-010 must enable it)")
    void numericEnumValuesAreRejectedWhenTheFeatureIsOn() {
        Map<String, Object> body = baseBody();
        body.put("actorType", 1);

        assertThatThrownBy(() -> STRICT_ENUM_MAPPER.readValue(toJson(body), LoginEventDto.class))
                .isInstanceOf(JacksonException.class);
    }

    // ---------- occurredAt (AC 2) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("isoInstants")
    @DisplayName("ISO-8601 timestamps parse to the right instant")
    void occurredAtParsesIsoInstants(String raw, Instant expected) {
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            Map<String, Object> body = baseBody();
            body.put("occurredAt", raw);

            assertThat(read(type, body).occurredAt()).isEqualTo(expected);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"yesterday", "2026-01-02", "2026-13-45T00:00:00Z"})
    @DisplayName("a malformed timestamp fails at deserialization")
    void malformedOccurredAtFailsAtDeserialization(String raw) {
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            Map<String, Object> body = baseBody();
            body.put("occurredAt", raw);

            assertThatThrownBy(() -> read(type, body)).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    @DisplayName("report only: how a numeric occurredAt is treated")
    void numericOccurredAtIsReported(TestReporter reporter) {
        List<Object> values = List.of(1767323045L, 1767323045000L, 1767323045.123);
        for (Object value : values) {
            Map<String, Object> body = baseBody();
            body.put("occurredAt", value);
            String outcome;
            try {
                outcome = "ACCEPTED as " + read(LoginEventDto.class, body).occurredAt();
            } catch (JacksonException exception) {
                outcome = "REJECTED with " + exception.getClass().getSimpleName();
            }
            reporter.publishEntry("occurredAt " + value, outcome);
            assertThat(outcome).isNotBlank();
        }
    }

    // ---------- payload ----------

    @Test
    @DisplayName("payload decimals keep their exact value, so equal numbers canonicalize equally")
    void payloadDecimalsKeepTheirExactValue() {
        String raw = "{\"n\":1.10,\"p\":0.1234567890123456789,\"i\":2}";

        TrustEventDto dto = MAPPER.readValue(bodyWithRawPayload(raw), LoginEventDto.class);

        assertThat((BigDecimal) dto.payload().get("n")).isEqualByComparingTo(new BigDecimal("1.10"));
        assertThat((BigDecimal) dto.payload().get("p")).isEqualTo(new BigDecimal("0.1234567890123456789"));
        assertThat(CanonicalJsonSerializer.serialize(dto.payload()))
                .isEqualTo("{\"i\":2,\"n\":1.1,\"p\":0.1234567890123456789}");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"[]", "\"text\"", "5"})
    @DisplayName("a payload that is not a JSON object fails at deserialization")
    void payloadMustBeAJsonObject(String raw) {
        assertThatThrownBy(() -> MAPPER.readValue(bodyWithRawPayload(raw), LoginEventDto.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    @DisplayName("an absent or null payload deserializes to null (Ruling 57)")
    void absentOrNullPayloadIsNull() {
        Map<String, Object> absent = baseBody();
        absent.remove("payload");
        Map<String, Object> explicitNull = baseBody();
        explicitNull.put("payload", null);

        assertThat(read(LoginEventDto.class, absent).payload()).isNull();
        assertThat(read(LoginEventDto.class, explicitNull).payload()).isNull();
    }

    // ---------- JSON escapes reach the validator (Ruling 66) ----------

    private static boolean rejectedByParseOrValidation(String jsonEscape) {
        Map<String, Object> body = baseBody();
        body.put("sessionId", PLACEHOLDER);
        String json = toJson(body).replace(PLACEHOLDER, jsonEscape);
        TrustEventDto dto;
        try {
            dto = MAPPER.readValue(json, LoginEventDto.class);
        } catch (JacksonException exception) {
            return true;
        }
        return EventDtoFixture.paths(EventDtoFixture.validate(dto)).contains("sessionId");
    }

    @Test
    @DisplayName("JSON escapes for NUL and unpaired surrogates are rejected; a valid pair is accepted")
    void jsonEscapesForBadTextAreRejected() {
        assertThat(rejectedByParseOrValidation("\\" + "u0000")).as("NUL").isTrue();
        assertThat(rejectedByParseOrValidation("\\" + "ud800")).as("lone high surrogate").isTrue();
        assertThat(rejectedByParseOrValidation("\\" + "udc00")).as("lone low surrogate").isTrue();
        assertThat(rejectedByParseOrValidation("\\" + "ud83d" + "\\" + "ude00")).as("valid pair").isFalse();
    }
}