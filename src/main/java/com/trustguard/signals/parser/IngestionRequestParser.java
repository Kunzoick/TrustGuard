package com.trustguard.signals.parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.dto.TrustEventDto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns the raw bytes of an ingestion request into a validated event DTO (Rules 6.1 and 16.5,
 * Rulings 55, 67, 68, 86 and 93). It takes bytes, because the request limit filter has already
 * bounded and cached the body, and it never reads a stream and never logs.
 *
 * <p>Steps, each failing with {@link InvalidIngestionRequestException}:
 * <ol>
 *   <li>The bytes must be strict UTF-8, so Jackson never auto-detects UTF-16 or UTF-32.</li>
 *   <li>The text must be one JSON document: no duplicate key (Ruling 93), nesting at most 10 levels,
 *       nothing after it.</li>
 *   <li>The root must be an object. The text fields, occurredAt and the enum fields must be JSON
 *       strings and the payload an object, so a number is never coerced into text or a timestamp.
 *       Every wrong-typed field is reported together.</li>
 *   <li>The object is bound to the DTO: an unknown property, an unknown enum value or an unparseable
 *       timestamp fails. receivedAt is silently ignored (Rule 6.1).</li>
 *   <li>One Bean Validation call; every violation is returned together. A violation whose message
 *       template is {@link SignalConstants#PAYLOAD_TOO_LARGE_MESSAGE} makes the code
 *       PAYLOAD_TOO_LARGE (Ruling 67), otherwise VALIDATION_FAILED.</li>
 * </ol>
 *
 * <p>Every message and detail is fixed text. Client text is never echoed, and no Jackson message or
 * cause is passed on, because those quote the input (Rule 14.7). Only our own field names and the
 * valid values of our own enums appear. The mapper is private and never a bean (Ruling 86).
 * Thread-safe once constructed.
 */
public final class IngestionRequestParser {

    private static final int MAX_NESTING_DEPTH = 10;

    private static final String FIELD_ACTOR_TYPE = "actorType";
    private static final String FIELD_ENVIRONMENT = "environment";
    private static final String FIELD_OCCURRED_AT = "occurredAt";
    private static final String FIELD_PAYLOAD = "payload";

    /** The client fields that must be JSON strings when present. */
    private static final List<String> TEXT_FIELDS = List.of(FIELD_ACTOR_TYPE, "actorId", "sessionId",
            FIELD_OCCURRED_AT, "sdkVersion", FIELD_ENVIRONMENT, "ipAddress", "userAgent", "deviceFingerprint",
            "idempotencyKey", "projectId");

    private static final String MESSAGE_INVALID_BODY = "Request body is not a valid event.";
    private static final String MESSAGE_UNKNOWN_FIELD = "Request body contains an unknown field.";
    private static final String MESSAGE_VALIDATION_FAILED = "Request validation failed.";
    private static final String MESSAGE_PAYLOAD_TOO_LARGE = "Event payload exceeds the maximum allowed size.";

    private static final String DETAIL_MUST_BE_TEXT = "must be a string";
    private static final String DETAIL_MUST_BE_OBJECT = "must be an object";
    private static final String DETAIL_MUST_BE_TIMESTAMP = "must be an ISO-8601 timestamp";
    private static final String DETAIL_MUST_BE_ONE_OF = "must be one of ";

    private static final Comparator<FieldDetail> DETAIL_ORDER =
            Comparator.comparing(FieldDetail::field).thenComparing(FieldDetail::message);

    private final JsonMapper mapper;
    private final Function<TrustEventDto, Set<ConstraintViolation<TrustEventDto>>> validate;

    /**
     * Creates the parser.
     *
     * @param validator the Bean Validation validator, normally Spring's
     */
    public IngestionRequestParser(Validator validator) {
        Objects.requireNonNull(validator, "validator");
        this.validate = event -> validator.validate(event);
        this.mapper = buildMapper();
    }

    /**
     * Parses and validates one request body.
     *
     * @param body the request body bytes, already bounded by the request limit filter
     * @param type the DTO type for the endpoint
     * @param <T>  the DTO type
     * @return the validated event
     * @throws InvalidIngestionRequestException if the body cannot be read, has the wrong shape or
     *                                          fails validation; messages are fixed text
     */
    public <T extends TrustEventDto> T parse(byte[] body, Class<T> type) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(type, "type");
        JsonNode root = readTree(decode(body));
        requireStructure(root);
        T event = bind(root, type);
        reportViolations(validate.apply(event));
        return event;
    }

    private static JsonMapper buildMapper() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .build();
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        return JsonMapper.builder(factory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .disable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .build();
    }

    private static String decode(byte[] body) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw invalid(MESSAGE_INVALID_BODY);
        }
    }

    private JsonNode readTree(String text) {
        try {
            return mapper.readTree(text);
        } catch (JacksonException ex) {
            throw invalid(MESSAGE_INVALID_BODY);
        }
    }

    private static void requireStructure(JsonNode root) {
        if (!root.isObject()) {
            throw invalid(MESSAGE_INVALID_BODY);
        }
        List<FieldDetail> wrongTypes = new ArrayList<>();
        for (String name : TEXT_FIELDS) {
            JsonNode node = root.get(name);
            if (node != null && !node.isNull() && !node.isTextual()) {
                wrongTypes.add(new FieldDetail(name, DETAIL_MUST_BE_TEXT));
            }
        }
        JsonNode payload = root.get(FIELD_PAYLOAD);
        if (payload != null && !payload.isNull() && !payload.isObject()) {
            wrongTypes.add(new FieldDetail(FIELD_PAYLOAD, DETAIL_MUST_BE_OBJECT));
        }
        if (!wrongTypes.isEmpty()) {
            wrongTypes.sort(DETAIL_ORDER);
            throw new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, MESSAGE_INVALID_BODY,
                    wrongTypes);
        }
    }

    private <T extends TrustEventDto> T bind(JsonNode root, Class<T> type) {
        try {
            return mapper.treeToValue(root, type);
        } catch (UnrecognizedPropertyException ex) {
            throw invalid(MESSAGE_UNKNOWN_FIELD);
        } catch (InvalidFormatException ex) {
            throw fromInvalidFormat(ex);
        } catch (JacksonException ex) {
            throw invalid(MESSAGE_INVALID_BODY);
        }
    }

    private static InvalidIngestionRequestException fromInvalidFormat(InvalidFormatException ex) {
        Class<?> target = ex.getTargetType();
        if (target == ActorType.class) {
            return invalidField(FIELD_ACTOR_TYPE, DETAIL_MUST_BE_ONE_OF + Arrays.toString(ActorType.values()));
        }
        if (target == Environment.class) {
            return invalidField(FIELD_ENVIRONMENT, DETAIL_MUST_BE_ONE_OF + Arrays.toString(Environment.values()));
        }
        if (target == Instant.class) {
            return invalidField(FIELD_OCCURRED_AT, DETAIL_MUST_BE_TIMESTAMP);
        }
        return invalid(MESSAGE_INVALID_BODY);
    }

    private static void reportViolations(Set<ConstraintViolation<TrustEventDto>> violations) {
        if (violations.isEmpty()) {
            return;
        }
        boolean payloadTooLarge = false;
        List<FieldDetail> details = new ArrayList<>();
        for (ConstraintViolation<TrustEventDto> violation : violations) {
            if (SignalConstants.PAYLOAD_TOO_LARGE_MESSAGE.equals(violation.getMessageTemplate())) {
                payloadTooLarge = true;
            }
            details.add(new FieldDetail(violation.getPropertyPath().toString(), violation.getMessage()));
        }
        details.sort(DETAIL_ORDER);
        if (payloadTooLarge) {
            throw new InvalidIngestionRequestException(ErrorCode.PAYLOAD_TOO_LARGE, MESSAGE_PAYLOAD_TOO_LARGE,
                    details);
        }
        throw new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, MESSAGE_VALIDATION_FAILED, details);
    }

    private static InvalidIngestionRequestException invalid(String message) {
        return new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, message);
    }

    private static InvalidIngestionRequestException invalidField(String field, String detail) {
        return new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, MESSAGE_INVALID_BODY,
                List.of(new FieldDetail(field, detail)));
    }
}