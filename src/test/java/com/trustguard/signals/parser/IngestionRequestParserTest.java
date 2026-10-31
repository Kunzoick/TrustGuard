package com.trustguard.signals.parser;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.dto.ContentSubmitEventDto;
import com.trustguard.signals.dto.LoginEventDto;
import com.trustguard.signals.dto.TransactionEventDto;
import com.trustguard.signals.dto.TrustEventDto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionRequestParserTest {

    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    private static final IngestionRequestParser PARSER = new IngestionRequestParser(VALIDATOR);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String MESSAGE_INVALID_BODY = "Request body is not a valid event.";
    private static final String MESSAGE_UNKNOWN_FIELD = "Request body contains an unknown field.";
    private static final String MESSAGE_VALIDATION_FAILED = "Request validation failed.";
    private static final String MESSAGE_PAYLOAD_TOO_LARGE = "Event payload exceeds the maximum allowed size.";
    private static final String MUST_BE_TEXT = "must be a string";
    private static final String MUST_BE_OBJECT = "must be an object";
    private static final String MUST_BE_ONE_OF = "must be one of ";

    private static final String PLACEHOLDER = "RAW_PAYLOAD_PLACEHOLDER";
    private static final String SECRET = "secret-value";
    private static final int PAYLOAD_ENVELOPE_BYTES = 8;
    private static final int MAX_PAYLOAD_BYTES = 32768;
    private static final int MAX_TEXT = 255;
    private static final int ALLOWED_PAYLOAD_NESTING = 8;

    private static final List<String> TEXT_FIELDS = List.of("actorType", "actorId", "sessionId", "occurredAt",
            "sdkVersion", "environment", "ipAddress", "userAgent", "deviceFingerprint", "idempotencyKey",
            "projectId");

    private static final Comparator<FieldDetail> DETAIL_ORDER =
            Comparator.comparing(FieldDetail::field).thenComparing(FieldDetail::message);

    // ---------- sources and helpers ----------

    static Stream<Class<? extends TrustEventDto>> types() {
        return Stream.of(LoginEventDto.class, TransactionEventDto.class, ContentSubmitEventDto.class);
    }

    static Stream<Arguments> typeAndEventType() {
        return Stream.of(
                Arguments.of(LoginEventDto.class, EventType.LOGIN),
                Arguments.of(TransactionEventDto.class, EventType.TRANSACTION),
                Arguments.of(ContentSubmitEventDto.class, EventType.CONTENT_SUBMIT));
    }

    private static Map<String, Object> validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "USER");
        body.put("actorId", "user-42");
        body.put("sessionId", "session-1");
        body.put("occurredAt", "2026-03-10T11:59:00Z");
        body.put("sdkVersion", "1.0.0");
        body.put("environment", "PRODUCTION");
        body.put("ipAddress", "203.0.113.7");
        body.put("userAgent", "Mozilla/5.0");
        body.put("deviceFingerprint", "fp-1");
        body.put("payload", new LinkedHashMap<>(Map.of("k", "v")));
        body.put("idempotencyKey", "idem-1");
        body.put("projectId", "123e4567-e89b-12d3-a456-426614174000");
        return body;
    }

    private static String json(Map<String, Object> body) {
        return MAPPER.writeValueAsString(body);
    }

    private static byte[] bytes(Map<String, Object> body) {
        return json(body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String rawJson) {
        return rawJson.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytesWithRawPayload(String rawPayload) {
        Map<String, Object> body = validBody();
        body.put("payload", PLACEHOLDER);
        return bytes(json(body).replace("\"" + PLACEHOLDER + "\"", rawPayload));
    }

    private static byte[] bytesWithPayloadText(int length) {
        Map<String, Object> body = validBody();
        body.put("payload", new LinkedHashMap<>(Map.of("k", "a".repeat(length))));
        return bytes(body);
    }

    private static LoginEventDto parse(byte[] body) {
        return PARSER.parse(body, LoginEventDto.class);
    }

    private static InvalidIngestionRequestException rejection(byte[] body) {
        try {
            parse(body);
        } catch (InvalidIngestionRequestException ex) {
            return ex;
        }
        throw new AssertionError("Expected the request to be rejected");
    }

    private static List<String> fields(InvalidIngestionRequestException exception) {
        return exception.details().stream().map(FieldDetail::field).distinct().toList();
    }

    private static void assertNoEcho(InvalidIngestionRequestException exception) {
        assertThat(exception.getMessage()).doesNotContain(SECRET);
        assertThat(exception.getCause()).isNull();
        for (FieldDetail detail : exception.details()) {
            assertThat(detail.field()).doesNotContain(SECRET);
            assertThat(detail.message()).doesNotContain(SECRET);
        }
    }

    private static String nestedArrays(int levels) {
        return "{\"n\":" + "[".repeat(levels) + "]".repeat(levels) + "}";
    }

    // ---------- valid requests ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("typeAndEventType")
    @DisplayName("a valid body parses into each of the three event types")
    void aValidBodyParsesIntoEachType(Class<? extends TrustEventDto> type, EventType expected) {
        TrustEventDto dto = PARSER.parse(bytes(validBody()), type);

        assertThat(dto).isInstanceOf(type);
        assertThat(dto.eventType()).isEqualTo(expected);
        assertThat(dto.actorType()).isEqualTo(ActorType.USER);
        assertThat(dto.actorId()).isEqualTo("user-42");
        assertThat(dto.environment()).isEqualTo(Environment.PRODUCTION);
        assertThat(dto.payload()).containsEntry("k", "v");
        assertThat(dto.projectId()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"2026-03-10T11:59:00Z", "2026-03-10T11:59:00.123456789Z", "2026-03-10T13:59:00+02:00"})
    @DisplayName("ISO-8601 timestamps with Z, fractions or an offset are accepted")
    void isoTimestampsAreAccepted(String timestamp) {
        Map<String, Object> body = validBody();
        body.put("occurredAt", timestamp);

        assertThat(parse(bytes(body)).occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("receivedAt in the body is silently ignored whatever it holds (Rule 6.1)")
    void receivedAtIsIgnored() {
        for (Object value : Arrays.asList("2020-01-01T00:00:00Z", 12345, Map.of("x", 1), List.of(1), true, null)) {
            Map<String, Object> body = validBody();
            body.put("receivedAt", value);

            assertThat(parse(bytes(body)).actorId()).as("receivedAt=" + value).isEqualTo("user-42");
        }
    }

    @Test
    @DisplayName("null optional fields and trailing whitespace are fine")
    void nullOptionalFieldsAndTrailingWhitespaceAreFine() {
        Map<String, Object> body = validBody();
        body.put("sessionId", null);
        body.put("userAgent", null);
        body.put("deviceFingerprint", null);
        body.put("payload", null);
        body.put("idempotencyKey", null);
        body.put("projectId", null);

        assertThat(parse(bytes(json(body) + "\n  \n")).payload()).isNull();
    }

    @Test
    @DisplayName("payload decimals keep their exact value and large integers become BigInteger")
    void payloadNumbersKeepTheirExactValue() {
        LoginEventDto dto = parse(bytesWithRawPayload(
                "{\"n\":1.10,\"p\":0.1234567890123456789,\"i\":12345678901234567890}"));

        assertThat((BigDecimal) dto.payload().get("n")).isEqualByComparingTo(new BigDecimal("1.10"));
        assertThat(dto.payload().get("p")).isEqualTo(new BigDecimal("0.1234567890123456789"));
        assertThat(dto.payload().get("i")).isEqualTo(new BigInteger("12345678901234567890"));
    }

    // ---------- bytes and JSON syntax ----------

    @Test
    @DisplayName("bytes that are not strict UTF-8 are rejected")
    void invalidUtf8IsRejected() {
        byte[] overlong = {'{', (byte) 0xC3, (byte) 0x28, '}'};

        InvalidIngestionRequestException rejected = rejection(overlong);

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_INVALID_BODY);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE"})
    @DisplayName("a body in UTF-16 or UTF-32 is not auto-detected, it is rejected")
    void otherEncodingsAreRejected(String charset) {
        byte[] body = json(validBody()).getBytes(java.nio.charset.Charset.forName(charset));

        assertThat(rejection(body).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "   ", "[]", "\"text\"", "5", "null", "true", "{", "{\"actorType\":", "not json",
            "{'actorType':'USER'}"})
    @DisplayName("an empty body, a non-object root or malformed JSON is rejected with a fixed message")
    void malformedOrNonObjectBodiesAreRejected(String raw) {
        InvalidIngestionRequestException rejected = rejection(bytes(raw));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_INVALID_BODY);
        assertThat(rejected.details()).isEmpty();
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"{}", " x", "}", "{\"actorType\":\"USER\"}", "[]"})
    @DisplayName("anything after the JSON document is rejected")
    void trailingContentIsRejected(String trailing) {
        assertThat(rejection(bytes(json(validBody()) + trailing)).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("a repeated key is rejected at the top level and inside the payload (Ruling 93)")
    void duplicateKeysAreRejected() {
        assertThat(rejection(bytes("{\"actorType\":\"USER\",\"actorType\":\"USER\"}")).getMessage())
                .isEqualTo(MESSAGE_INVALID_BODY);
        assertThat(rejection(bytesWithRawPayload("{\"a\":1,\"a\":2}")).getMessage())
                .isEqualTo(MESSAGE_INVALID_BODY);
        assertThat(rejection(bytesWithRawPayload("{\"outer\":{\"a\":1,\"a\":1}}")).getMessage())
                .isEqualTo(MESSAGE_INVALID_BODY);
    }

    @Test
    @DisplayName("nesting of 10 levels passes and 11 is rejected (Rule 16.5)")
    void nestingDepthIsBounded() {
        assertThat(parse(bytesWithRawPayload(nestedArrays(ALLOWED_PAYLOAD_NESTING))).payload()).containsKey("n");

        InvalidIngestionRequestException rejected =
                rejection(bytesWithRawPayload(nestedArrays(ALLOWED_PAYLOAD_NESTING + 1)));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_INVALID_BODY);
    }

    @Test
    @DisplayName("a lone surrogate escaped in JSON never gets through")
    void loneSurrogateEscapeIsRejected() {
        String raw = "{\"sessionId\":\"\\" + "ud800\"}";

        assertThat(rejection(bytes(raw)).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------- types ----------

    @ParameterizedTest(name = "{0}")
    @MethodSource("textFieldNames")
    @DisplayName("a number, boolean, array or object where text is required is rejected with the field name")
    void wrongTypedTextFieldsAreRejected(String field) {
        for (Object bad : List.of(42, true, List.of("x"), Map.of("x", 1))) {
            Map<String, Object> body = validBody();
            body.put(field, bad);

            InvalidIngestionRequestException rejected = rejection(bytes(body));

            assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(rejected.getMessage()).isEqualTo(MESSAGE_INVALID_BODY);
            assertThat(rejected.details()).containsExactly(new FieldDetail(field, MUST_BE_TEXT));
        }
    }

    static Stream<String> textFieldNames() {
        return TEXT_FIELDS.stream();
    }

    @Test
    @DisplayName("a numeric occurredAt is never coerced into a timestamp")
    void aNumericTimestampIsRejected() {
        for (Object number : List.of(1767323045L, 1767323045000L, 1767323045.123)) {
            Map<String, Object> body = validBody();
            body.put("occurredAt", number);

            assertThat(rejection(bytes(body)).details())
                    .containsExactly(new FieldDetail("occurredAt", MUST_BE_TEXT));
        }
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"[]", "\"text\"", "5", "true"})
    @DisplayName("a payload that is not an object is rejected")
    void aNonObjectPayloadIsRejected(String rawPayload) {
        assertThat(rejection(bytesWithRawPayload(rawPayload)).details())
                .containsExactly(new FieldDetail("payload", MUST_BE_OBJECT));
    }

    @Test
    @DisplayName("every wrong-typed field is reported together, sorted by field name")
    void wrongTypedFieldsAreReportedTogether() {
        Map<String, Object> body = validBody();
        body.put("sdkVersion", 1);
        body.put("ipAddress", true);
        body.put("payload", List.of());

        assertThat(rejection(bytes(body)).details()).containsExactly(
                new FieldDetail("ipAddress", MUST_BE_TEXT),
                new FieldDetail("payload", MUST_BE_OBJECT),
                new FieldDetail("sdkVersion", MUST_BE_TEXT));
    }

    // ---------- unknown properties and enums ----------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tenantId", "eventId", "eventType", "secret-value", "projectid"})
    @DisplayName("tenantId, eventId, eventType and any other unknown property fail without naming it")
    void unknownPropertiesAreRejected(String name) {
        Map<String, Object> body = validBody();
        body.put(name, "x");

        InvalidIngestionRequestException rejected = rejection(bytes(body));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_UNKNOWN_FIELD);
        assertThat(rejected.getMessage()).doesNotContain(name);
        assertThat(rejected.details()).isEmpty();
    }

    @Test
    @DisplayName("an unknown actorType or environment lists the valid values of our own enum and never the input")
    void unknownEnumValuesListTheValidValues() {
        Map<String, Object> badActor = validBody();
        badActor.put("actorType", SECRET);
        Map<String, Object> badEnvironment = validBody();
        badEnvironment.put("environment", SECRET);
        Map<String, Object> lowercase = validBody();
        lowercase.put("actorType", "user");

        InvalidIngestionRequestException actor = rejection(bytes(badActor));
        InvalidIngestionRequestException environment = rejection(bytes(badEnvironment));

        assertThat(actor.details()).containsExactly(
                new FieldDetail("actorType", MUST_BE_ONE_OF + Arrays.toString(ActorType.values())));
        assertThat(environment.details()).containsExactly(
                new FieldDetail("environment", MUST_BE_ONE_OF + Arrays.toString(Environment.values())));
        assertThat(rejection(bytes(lowercase)).details()).extracting(FieldDetail::field).containsExactly("actorType");
        assertNoEcho(actor);
        assertNoEcho(environment);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"yesterday", "2026-03-10", "2026-13-45T00:00:00Z", "secret-value", "2026-03-10T11:59:00"})
    @DisplayName("an unparseable timestamp is rejected without echoing it")
    void anUnparseableTimestampIsRejected(String timestamp) {
        Map<String, Object> body = validBody();
        body.put("occurredAt", timestamp);

        InvalidIngestionRequestException rejected = rejection(bytes(body));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.details()).allSatisfy(detail -> assertThat(detail.field()).isEqualTo("occurredAt"));
        assertNoEcho(rejected);
    }

    // ---------- Bean Validation (Ruling 55) ----------

    @Test
    @DisplayName("an empty object reports every missing required field together")
    void missingRequiredFieldsAreReportedTogether() {
        InvalidIngestionRequestException rejected = rejection(bytes("{}"));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_VALIDATION_FAILED);
        assertThat(fields(rejected)).containsExactlyInAnyOrder("actorType", "environment", "ipAddress",
                "occurredAt", "sdkVersion");
    }

    @Test
    @DisplayName("every violation of one body comes back together, sorted, without any submitted value")
    void everyViolationIsReportedTogether() {
        Map<String, Object> body = validBody();
        body.put("actorId", "");
        body.put("sessionId", "");
        body.put("sdkVersion", SECRET + "a".repeat(MAX_TEXT));
        body.put("ipAddress", "a".repeat(46));
        body.put("userAgent", "a".repeat(513));
        body.put("projectId", "not-a-uuid");
        body.put("payload", new LinkedHashMap<>(Map.of("k", String.valueOf((char) 0))));

        InvalidIngestionRequestException rejected = rejection(bytes(body));

        assertThat(rejected.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_VALIDATION_FAILED);
        assertThat(fields(rejected)).containsExactlyInAnyOrder("actorId", "sessionId", "sdkVersion", "ipAddress",
                "userAgent", "projectId", "payload");
        assertThat(rejected.details()).isSortedAccordingTo(DETAIL_ORDER);
        assertNoEcho(rejected);
    }

    @Test
    @DisplayName("an ANONYMOUS event that carries an actorId is rejected on actorId")
    void anonymousWithActorIdIsRejected() {
        Map<String, Object> body = validBody();
        body.put("actorType", "ANONYMOUS");

        assertThat(fields(rejection(bytes(body)))).containsExactly("actorId");
    }

    // ---------- oversize payload field (Ruling 67) ----------

    @Test
    @DisplayName("a payload of exactly 32768 canonical bytes passes and one more is PAYLOAD_TOO_LARGE")
    void payloadSizeBoundary() {
        int fits = MAX_PAYLOAD_BYTES - PAYLOAD_ENVELOPE_BYTES;

        assertThat(parse(bytesWithPayloadText(fits)).payload()).containsKey("k");

        InvalidIngestionRequestException rejected = rejection(bytesWithPayloadText(fits + 1));

        assertThat(rejected.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(rejected.getMessage()).isEqualTo(MESSAGE_PAYLOAD_TOO_LARGE);
        assertThat(fields(rejected)).containsExactly("payload");
        assertThat(rejected.retryable()).isFalse();
    }

    @Test
    @DisplayName("an oversize payload with other violations is PAYLOAD_TOO_LARGE and still lists every violation")
    void oversizeWinsTheCodeButListsEverything() {
        Map<String, Object> body = validBody();
        body.put("payload", new LinkedHashMap<>(Map.of("k", "a".repeat(MAX_PAYLOAD_BYTES))));
        body.put("sdkVersion", "a".repeat(MAX_TEXT + 1));

        InvalidIngestionRequestException rejected = rejection(bytes(body));

        assertThat(rejected.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(fields(rejected)).containsExactlyInAnyOrder("payload", "sdkVersion");
    }

    @Test
    @DisplayName("payload text with NUL is VALIDATION_FAILED, not PAYLOAD_TOO_LARGE")
    void otherPayloadViolationsAreNotTooLarge() {
        Map<String, Object> body = validBody();
        body.put("payload", new LinkedHashMap<>(Map.of("k", String.valueOf((char) 0))));

        assertThat(rejection(bytes(body)).code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    // ---------- echo and construction ----------

    @Test
    @DisplayName("no failure echoes client text, whatever fails")
    void nothingIsEchoed() {
        Map<String, Object> unknownField = validBody();
        unknownField.put(SECRET, 1);
        Map<String, Object> wrongType = validBody();
        wrongType.put("sdkVersion", 1);
        Map<String, Object> tooLong = validBody();
        tooLong.put("deviceFingerprint", SECRET + "a".repeat(MAX_TEXT));

        List<byte[]> bodies = List.of(bytes(unknownField), bytes(wrongType), bytes(tooLong),
                bytes("{\"actorType\": " + SECRET), bytes("{\"" + SECRET + "\": }"), bytes(SECRET));

        for (byte[] body : bodies) {
            assertNoEcho(rejection(body));
        }
    }

    @Test
    @DisplayName("the parser holds no logger, so nothing from a body can be logged")
    void theParserHoldsNoLogger() {
        assertThat(Arrays.stream(IngestionRequestParser.class.getDeclaredFields())
                .map(field -> field.getType().getName()))
                .noneMatch(name -> name.startsWith("org.slf4j") || name.startsWith("java.util.logging"));
    }

    @Test
    @DisplayName("null arguments are programming errors")
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> new IngestionRequestParser(null))
                .isInstanceOf(NullPointerException.class).hasMessage("validator");
        assertThatThrownBy(() -> PARSER.parse(null, LoginEventDto.class))
                .isInstanceOf(NullPointerException.class).hasMessage("body");
        assertThatThrownBy(() -> PARSER.parse(new byte[0], null))
                .isInstanceOf(NullPointerException.class).hasMessage("type");
    }

    @Test
    @DisplayName("the three event types are the only ones the parser needs: sealed interface, three records")
    void allThreeTypesAreCovered() {
        assertThat(Set.of(TrustEventDto.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrder(LoginEventDto.class, TransactionEventDto.class,
                        ContentSubmitEventDto.class);
    }
}