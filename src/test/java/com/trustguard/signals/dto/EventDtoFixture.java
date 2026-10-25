package com.trustguard.signals.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Environment;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * Test fixture for the event DTOs (Rule 17.6): the only place that calls the 12-component
 * record constructors positionally. Public since B-009c so the mapper test in another package
 * can use it. The validator avoids an Expression Language dependency by using parameter-only
 * message interpolation; the signals templates hold no expressions.
 */
public final class EventDtoFixture {

    public static final List<Class<? extends TrustEventDto>> TYPES =
            List.of(LoginEventDto.class, TransactionEventDto.class, ContentSubmitEventDto.class);

    public static final String PROJECT_ID = "123e4567-e89b-12d3-a456-426614174000";

    public static final Instant OCCURRED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    private EventDtoFixture() {
    }

    /**
     * Starts a builder holding a valid USER event.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates a DTO with Bean Validation.
     *
     * @param dto the event
     * @return the violations, empty when valid
     */
    public static Set<ConstraintViolation<TrustEventDto>> validate(TrustEventDto dto) {
        return VALIDATOR.validate(dto);
    }

    /**
     * Collects the property paths of violations.
     *
     * @param violations the violations
     * @return the distinct property paths
     */
    public static Set<String> paths(Set<ConstraintViolation<TrustEventDto>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    /** Builds a valid USER event by default; each method changes one field. */
    public static final class Builder {

        private ActorType actorType = ActorType.USER;
        private String actorId = "user-42";
        private String sessionId = "session-1";
        private Instant occurredAt = OCCURRED_AT;
        private String sdkVersion = "1.0.0";
        private Environment environment = Environment.PRODUCTION;
        private String ipAddress = "203.0.113.7";
        private String userAgent = "Mozilla/5.0";
        private String deviceFingerprint = "fp-1";
        private Map<String, Object> payload = new LinkedHashMap<>(Map.of("k", "v"));
        private String idempotencyKey = "idem-1";
        private String projectId = PROJECT_ID;

        public Builder actorType(ActorType value) {
            this.actorType = value;
            return this;
        }

        public Builder actorId(String value) {
            this.actorId = value;
            return this;
        }

        public Builder sessionId(String value) {
            this.sessionId = value;
            return this;
        }

        public Builder occurredAt(Instant value) {
            this.occurredAt = value;
            return this;
        }

        public Builder sdkVersion(String value) {
            this.sdkVersion = value;
            return this;
        }

        public Builder environment(Environment value) {
            this.environment = value;
            return this;
        }

        public Builder ipAddress(String value) {
            this.ipAddress = value;
            return this;
        }

        public Builder userAgent(String value) {
            this.userAgent = value;
            return this;
        }

        public Builder deviceFingerprint(String value) {
            this.deviceFingerprint = value;
            return this;
        }

        public Builder payload(Map<String, Object> value) {
            this.payload = value;
            return this;
        }

        public Builder idempotencyKey(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder projectId(String value) {
            this.projectId = value;
            return this;
        }

        /**
         * Builds the DTO of the given record type.
         *
         * @param type one of {@link #TYPES}
         * @return the event
         */
        public TrustEventDto build(Class<? extends TrustEventDto> type) {
            if (type == LoginEventDto.class) {
                return new LoginEventDto(actorType, actorId, sessionId, occurredAt, sdkVersion, environment,
                        ipAddress, userAgent, deviceFingerprint, payload, idempotencyKey, projectId);
            }
            if (type == TransactionEventDto.class) {
                return new TransactionEventDto(actorType, actorId, sessionId, occurredAt, sdkVersion, environment,
                        ipAddress, userAgent, deviceFingerprint, payload, idempotencyKey, projectId);
            }
            if (type == ContentSubmitEventDto.class) {
                return new ContentSubmitEventDto(actorType, actorId, sessionId, occurredAt, sdkVersion,
                        environment, ipAddress, userAgent, deviceFingerprint, payload, idempotencyKey, projectId);
            }
            throw new IllegalArgumentException("Unknown event dto type: " + type.getName());
        }
    }
}