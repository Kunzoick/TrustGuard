package com.trustguard.signals.dto;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.util.CanonicalJsonSerializer;

import jakarta.validation.ConstraintViolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventDtoValidationTest {

    private static final int ENVELOPE_BYTES = 8;
    private static final int AMPLIFIED_NUMBER_COUNT = 100;
    private static final String SECRET_TEXT = "secret-text";

    private static final List<String> COMPONENTS = List.of("actorType", "actorId", "sessionId", "occurredAt",
            "sdkVersion", "environment", "ipAddress", "userAgent", "deviceFingerprint", "payload",
            "idempotencyKey", "projectId");

    private record TextField(String path, int max, BiConsumer<EventDtoFixture.Builder, String> setter) {
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

    static Stream<Arguments> boundaryCases() {
        int room = SignalConstants.MAX_PAYLOAD_BYTES - ENVELOPE_BYTES;
        Map<String, String> fillers = new LinkedHashMap<>();
        fillers.put("ASCII", "a".repeat(room));
        fillers.put("two-byte", "\u00e9".repeat(room / 2));
        fillers.put("three-byte", "\u20ac".repeat(room / 3));
        fillers.put("escaped control characters", String.valueOf((char) 0x01).repeat(room / 6));
        return EventDtoFixture.TYPES.stream().flatMap(type -> fillers.entrySet().stream()
                .map(filler -> Arguments.of(type, filler.getKey(), filler.getValue())));
    }

    private static List<TextField> textFields() {
        return List.of(
                new TextField("actorId", SignalConstants.MAX_TEXT_LENGTH, EventDtoFixture.Builder::actorId),
                new TextField("sessionId", SignalConstants.MAX_TEXT_LENGTH, EventDtoFixture.Builder::sessionId),
                new TextField("sdkVersion", SignalConstants.MAX_TEXT_LENGTH, EventDtoFixture.Builder::sdkVersion),
                new TextField("ipAddress", SignalConstants.MAX_IP_ADDRESS_LENGTH,
                        EventDtoFixture.Builder::ipAddress),
                new TextField("userAgent", SignalConstants.MAX_USER_AGENT_LENGTH,
                        EventDtoFixture.Builder::userAgent),
                new TextField("deviceFingerprint", SignalConstants.MAX_TEXT_LENGTH,
                        EventDtoFixture.Builder::deviceFingerprint),
                new TextField("idempotencyKey", SignalConstants.MAX_TEXT_LENGTH,
                        EventDtoFixture.Builder::idempotencyKey));
    }

    private static Set<ConstraintViolation<TrustEventDto>> check(Class<? extends TrustEventDto> type,
                                                                 Consumer<EventDtoFixture.Builder> change) {
        EventDtoFixture.Builder builder = EventDtoFixture.builder();
        change.accept(builder);
        return EventDtoFixture.validate(builder.build(type));
    }

    private static List<String> templates(Set<ConstraintViolation<TrustEventDto>> violations) {
        return violations.stream().map(ConstraintViolation::getMessageTemplate).toList();
    }

    private static Map<String, Object> entry(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    private static Map<String, Object> payloadWithText(String text) {
        return entry("k", text);
    }

    private static int canonicalBytes(Map<String, Object> payload) {
        return CanonicalJsonSerializer.serializeToBytes(payload).length;
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

    private static void assertOnlyPayloadViolation(Set<ConstraintViolation<TrustEventDto>> violations,
                                                   String template) {
        assertThat(EventDtoFixture.paths(violations)).containsExactly("payload");
        assertThat(violations).hasSize(1);
        ConstraintViolation<TrustEventDto> violation = violations.iterator().next();
        assertThat(violation.getMessageTemplate()).isEqualTo(template);
        assertThat(violation.getMessage()).isEqualTo(template);
    }

    // ---------- baseline and structure ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a valid event has no violations")
    void validBaselineHasNoViolations(Class<? extends TrustEventDto> type) {
        assertThat(check(type, builder -> { })).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeAndEventType")
    @DisplayName("eventType() is the constant of each record")
    void eventTypeIsTheRecordsConstant(Class<? extends TrustEventDto> type, EventType expected) {
        assertThat(EventDtoFixture.builder().build(type).eventType()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the three records declare the same components, constraints and class annotations")
    void allThreeRecordsDeclareIdenticalConstraints() {
        Class<? extends TrustEventDto> baseline = LoginEventDto.class;
        Map<String, List<String>> expected = constraintsByComponent(baseline);
        assertThat(expected.get("actorType:" + ActorType.class.getName())).isNotEmpty();
        for (Class<? extends TrustEventDto> type : EventDtoFixture.TYPES) {
            assertThat(Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList())
                    .as(type.getSimpleName() + " components").isEqualTo(COMPONENTS);
            assertThat(constraintsByComponent(type)).as(type.getSimpleName()).isEqualTo(expected);
            assertThat(classAnnotations(type)).as(type.getSimpleName() + " class annotations")
                    .isEqualTo(classAnnotations(baseline));
        }
    }

    private static Map<String, List<String>> constraintsByComponent(Class<?> type) {
        Map<String, List<String>> result = new TreeMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            try {
                Field field = type.getDeclaredField(component.getName());
                result.put(component.getName() + ":" + component.getGenericType().getTypeName(),
                        Arrays.stream(field.getAnnotations()).map(Annotation::toString).sorted().toList());
            } catch (NoSuchFieldException ex) {
                throw new IllegalStateException("No field for record component " + component.getName(), ex);
            }
        }
        return result;
    }

    private static List<String> classAnnotations(Class<?> type) {
        return Arrays.stream(type.getAnnotations()).map(Annotation::toString).sorted().toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("toString never prints client data (Rule 14.7)")
    void toStringDoesNotPrintClientData(Class<? extends TrustEventDto> type) {
        TrustEventDto dto = EventDtoFixture.builder()
                .actorId("actor-secret")
                .sessionId("session-secret")
                .ipAddress("198.51.100.77")
                .userAgent("agent-secret")
                .deviceFingerprint("fingerprint-secret")
                .idempotencyKey("idem-secret")
                .payload(entry("token", "payload-secret"))
                .build(type);

        assertThat(dto.toString()).contains(type.getSimpleName()).contains("USER").contains("PRODUCTION")
                .doesNotContain("actor-secret", "session-secret", "198.51.100.77", "agent-secret",
                        "fingerprint-secret", "idem-secret", "payload-secret");
    }

    // ---------- required fields and caps ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("every missing required field is reported in one validate call")
    void missingRequiredFieldsAreAllReported(Class<? extends TrustEventDto> type) {
        Set<ConstraintViolation<TrustEventDto>> violations = check(type, builder -> builder
                .actorType(null).occurredAt(null).sdkVersion("  ").environment(null).ipAddress(""));

        assertThat(EventDtoFixture.paths(violations)).containsExactlyInAnyOrder(
                "actorType", "occurredAt", "sdkVersion", "environment", "ipAddress");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("violations of every field of one invalid event come back together")
    void everyViolationOfOneDtoIsReportedTogether(Class<? extends TrustEventDto> type) {
        Set<ConstraintViolation<TrustEventDto>> violations = check(type, builder -> builder
                .actorId("")
                .sessionId("")
                .sdkVersion("a".repeat(SignalConstants.MAX_TEXT_LENGTH + 1))
                .ipAddress("a".repeat(SignalConstants.MAX_IP_ADDRESS_LENGTH + 1))
                .userAgent("a".repeat(SignalConstants.MAX_USER_AGENT_LENGTH + 1))
                .deviceFingerprint("a".repeat(SignalConstants.MAX_TEXT_LENGTH + 1))
                .idempotencyKey("a".repeat(SignalConstants.MAX_TEXT_LENGTH + 1))
                .projectId("not-a-uuid")
                .payload(entry("k", new Object())));

        assertThat(EventDtoFixture.paths(violations)).containsExactlyInAnyOrder("actorId", "sessionId",
                "sdkVersion", "ipAddress", "userAgent", "deviceFingerprint", "idempotencyKey", "projectId",
                "payload");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("each text field accepts exactly its cap and rejects one more character")
    void textFieldsAcceptTheirCapAndRejectOneMore(Class<? extends TrustEventDto> type) {
        for (TextField field : textFields()) {
            assertThat(check(type, builder -> field.setter().accept(builder, "a".repeat(field.max()))))
                    .as(field.path() + " at its cap").isEmpty();
            Set<ConstraintViolation<TrustEventDto>> over =
                    check(type, builder -> field.setter().accept(builder, "a".repeat(field.max() + 1)));
            assertThat(EventDtoFixture.paths(over)).as(field.path() + " over its cap")
                    .containsExactly(field.path());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an empty string is rejected in every text field")
    void emptyTextIsRejected(Class<? extends TrustEventDto> type) {
        for (TextField field : textFields()) {
            Set<ConstraintViolation<TrustEventDto>> violations =
                    check(type, builder -> field.setter().accept(builder, ""));
            assertThat(EventDtoFixture.paths(violations)).as(field.path()).containsExactly(field.path());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("optional fields may be null")
    void nullOptionalFieldsAreValid(Class<? extends TrustEventDto> type) {
        assertThat(check(type, builder -> builder.sessionId(null).userAgent(null).deviceFingerprint(null)
                .payload(null).idempotencyKey(null).projectId(null))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("projectId must be a UUID in 8-4-4-4-12 form, either letter case")
    void projectIdMustBeAUuid(Class<? extends TrustEventDto> type) {
        for (String valid : List.of(EventDtoFixture.PROJECT_ID, EventDtoFixture.PROJECT_ID.toUpperCase())) {
            assertThat(check(type, builder -> builder.projectId(valid))).as(valid).isEmpty();
        }
        for (String invalid : List.of("", "not-a-uuid", "123e4567e89b12d3a456426614174000",
                "123e4567-e89b-12d3-a456-42661417400", "123e4567-e89b-12d3-a456-4266141740000",
                "123e4567-e89b-12d3-a456-42661417400g", EventDtoFixture.PROJECT_ID + "\n",
                " " + EventDtoFixture.PROJECT_ID)) {
            assertThat(EventDtoFixture.paths(check(type, builder -> builder.projectId(invalid))))
                    .as("projectId '" + invalid.strip() + "'").containsExactly("projectId");
        }
    }

    // ---------- text hygiene (Ruling 66) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("NUL and unpaired surrogates are rejected in every text field without echoing the text")
    void textWithNulOrUnpairedSurrogateIsRejected(Class<? extends TrustEventDto> type) {
        List<String> bad = List.of(SECRET_TEXT + (char) 0, SECRET_TEXT + (char) 0xD800,
                SECRET_TEXT + (char) 0xDC00, String.valueOf((char) 0xDC00) + (char) 0xD800);
        for (TextField field : textFields()) {
            for (String text : bad) {
                Set<ConstraintViolation<TrustEventDto>> violations =
                        check(type, builder -> field.setter().accept(builder, text));
                assertThat(EventDtoFixture.paths(violations)).as(field.path()).containsExactly(field.path());
                assertThat(templates(violations)).containsExactly(SignalConstants.TEXT_NOT_WELL_FORMED_MESSAGE);
                assertThat(violations.iterator().next().getMessage()).doesNotContain(SECRET_TEXT);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("valid surrogate pairs and non-ASCII text pass in every text field")
    void validPairsAndNonAsciiTextPass(Class<? extends TrustEventDto> type) {
        String emoji = new String(Character.toChars(0x1F600));
        for (TextField field : textFields()) {
            for (String text : List.of("caf\u00e9", "ok-" + emoji, "\u2713")) {
                assertThat(check(type, builder -> field.setter().accept(builder, text)))
                        .as(field.path() + " with " + text).isEmpty();
            }
        }
    }

    // ---------- actorId against actorType (Rule 6.3) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("USER and SERVICE events need a non-blank actorId")
    void userAndServiceNeedAnActorId(Class<? extends TrustEventDto> type) {
        for (ActorType actorType : List.of(ActorType.USER, ActorType.SERVICE)) {
            for (String missing : new String[] {null, "   "}) {
                Set<ConstraintViolation<TrustEventDto>> violations =
                        check(type, builder -> builder.actorType(actorType).actorId(missing));
                assertThat(EventDtoFixture.paths(violations)).as(actorType + " with " + missing)
                        .containsExactly("actorId");
                assertThat(templates(violations)).containsExactly(SignalConstants.ACTOR_ID_REQUIRED_MESSAGE);
            }
            assertThat(check(type, builder -> builder.actorType(actorType).actorId("id-1"))).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an ANONYMOUS event must not carry an actorId, and needs no sessionId at DTO level")
    void anonymousMustNotCarryAnActorId(Class<? extends TrustEventDto> type) {
        Set<ConstraintViolation<TrustEventDto>> violations =
                check(type, builder -> builder.actorType(ActorType.ANONYMOUS).actorId("tenant-supplied"));

        assertThat(EventDtoFixture.paths(violations)).containsExactly("actorId");
        assertThat(templates(violations)).containsExactly(SignalConstants.ACTOR_ID_FORBIDDEN_MESSAGE);
        assertThat(check(type, builder -> builder.actorType(ActorType.ANONYMOUS).actorId(null))).isEmpty();
        assertThat(check(type, builder -> builder.actorType(ActorType.ANONYMOUS).actorId(null)
                .sessionId(null))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a missing actorType is reported once and not as an actorId problem")
    void missingActorTypeIsReportedOnce(Class<? extends TrustEventDto> type) {
        assertThat(EventDtoFixture.paths(check(type, builder -> builder.actorType(null))))
                .containsExactly("actorType");
    }

    // ---------- payload (Rules 16.5, Rulings 52, 66, 67) ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an absent, null, empty or ordinary payload is valid")
    void payloadAbsentNullEmptyOrOrdinaryIsValid(Class<? extends TrustEventDto> type) {
        assertThat(check(type, builder -> builder.payload(null))).isEmpty();
        assertThat(check(type, builder -> builder.payload(new LinkedHashMap<>()))).isEmpty();
        assertThat(check(type, builder -> builder.payload(entry("amount", new BigDecimal("12.50"))))).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("an unsupported payload value is reported on payload only, without echoing content")
    void unsupportedPayloadValueIsRejected(Class<? extends TrustEventDto> type) {
        List<Map<String, Object>> unsupported = List.of(
                entry("secret-key", new Object()),
                entry("n", Double.NaN),
                entry("n", Double.POSITIVE_INFINITY),
                entry("m", Map.of(1, "x")),
                entry("s", Set.of(1)),
                entry("n", new BigDecimal("1E" + (SignalConstants.MAX_NUMBER_SCALE + 1))));
        for (Map<String, Object> payload : unsupported) {
            assertOnlyPayloadViolation(check(type, builder -> builder.payload(payload)),
                    SignalConstants.PAYLOAD_UNSUPPORTED_MESSAGE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("payload nesting of 32 containers passes and 33 is rejected")
    void payloadNestingBound(Class<? extends TrustEventDto> type) {
        int allowedInside = SignalConstants.MAX_NESTING_DEPTH - 1;

        assertThat(check(type, builder -> builder.payload(entry("n", nested(allowedInside))))).isEmpty();
        assertOnlyPayloadViolation(check(type, builder -> builder.payload(entry("n", nested(allowedInside + 1)))),
                SignalConstants.PAYLOAD_UNSUPPORTED_MESSAGE);
    }

    /**
     * The validator relies on the serializer's depth bound to stop a self-referencing payload
     * before its own tree walk runs. A DTO-level version of this test cannot exist: the record's
     * generated hashCode() includes payload.hashCode(), which recurses forever on a cycle, so
     * Hibernate Validator would overflow the stack while hashing a violation. A cycle cannot arise
     * in production anyway, because the DTO comes from a parsed request body.
     */
    @Test
    @DisplayName("the serializer rejects a self-referencing payload, which the validator relies on")
    void serializerRejectsASelfReferencingPayload() {
        Map<String, Object> cycle = new LinkedHashMap<>();
        cycle.put("self", cycle);

        assertThatThrownBy(() -> CanonicalJsonSerializer.serialize(cycle))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{1} - {0}")
    @MethodSource("boundaryCases")
    @DisplayName("exactly 32768 canonical bytes pass and one more fails")
    void exactlyThirtyTwoKilobytesPassesAndOneMoreFails(Class<? extends TrustEventDto> type, String label,
                                                        String text) {
        assertThat(canonicalBytes(payloadWithText(text))).isEqualTo(SignalConstants.MAX_PAYLOAD_BYTES);
        assertThat(check(type, builder -> builder.payload(payloadWithText(text)))).isEmpty();
        assertOnlyPayloadViolation(check(type, builder -> builder.payload(payloadWithText(text + "a"))),
                SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("a multi-byte payload over the limit is measured in bytes, not characters")
    void multiByteOverflowIsMeasuredInBytes(Class<? extends TrustEventDto> type) {
        int room = SignalConstants.MAX_PAYLOAD_BYTES - ENVELOPE_BYTES;
        for (String text : List.of("\u00e9".repeat(room / 2) + "a", "\u20ac".repeat(room / 3) + "a")) {
            Map<String, Object> payload = payloadWithText(text);

            assertThat(canonicalBytes(payload)).isEqualTo(SignalConstants.MAX_PAYLOAD_BYTES + 1);
            assertThat(CanonicalJsonSerializer.serialize(payload).length())
                    .isLessThan(SignalConstants.MAX_CANONICAL_CHARS);
            assertOnlyPayloadViolation(check(type, builder -> builder.payload(payload)),
                    SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("many amplified numbers are reported as too large")
    void amplifiedNumbersAreReportedAsTooLarge(Class<? extends TrustEventDto> type) {
        List<Object> numbers = new ArrayList<>();
        for (int i = 0; i < AMPLIFIED_NUMBER_COUNT; i++) {
            numbers.add(new BigDecimal("1E" + SignalConstants.MAX_NUMBER_SCALE));
        }

        assertOnlyPayloadViolation(check(type, builder -> builder.payload(entry("n", numbers))),
                SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("NUL and unpaired surrogates in payload values or keys are rejected without echoing")
    void payloadTextHygiene(Class<? extends TrustEventDto> type) {
        for (String bad : List.of(SECRET_TEXT + (char) 0, SECRET_TEXT + (char) 0xD800,
                SECRET_TEXT + (char) 0xDC00)) {
            List<Map<String, Object>> payloads = List.of(
                    payloadWithText(bad),
                    entry(bad, "v"),
                    entry("list", List.of("ok", bad)),
                    entry("outer", entry("inner", bad)),
                    entry("outer", entry(bad, 1)));
            for (Map<String, Object> payload : payloads) {
                Set<ConstraintViolation<TrustEventDto>> violations =
                        check(type, builder -> builder.payload(payload));
                assertOnlyPayloadViolation(violations, SignalConstants.PAYLOAD_TEXT_MESSAGE);
                assertThat(violations.iterator().next().getMessage()).doesNotContain(SECRET_TEXT);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    @DisplayName("escaped-looking text, valid pairs and non-ASCII are not false positives")
    void harmlessPayloadTextPasses(Class<? extends TrustEventDto> type) {
        String escapedLooking = "\\" + "u0000";
        String emoji = new String(Character.toChars(0x1F600));
        List<Map<String, Object>> payloads = List.of(
                payloadWithText(escapedLooking),
                entry(escapedLooking, "v"),
                payloadWithText("back\\slash \"quoted\""),
                payloadWithText("caf\u00e9 " + emoji),
                entry(emoji, emoji));
        for (Map<String, Object> payload : payloads) {
            assertThat(check(type, builder -> builder.payload(payload))).isEmpty();
        }
    }
}