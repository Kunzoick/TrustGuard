package com.trustguard.signals.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.enums.EventType;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.signals.domain.IngestionContext;
import com.trustguard.signals.dto.EventDtoFixture;
import com.trustguard.signals.dto.LoginEventDto;
import com.trustguard.signals.mapper.EventEnvelopeMapper;
import com.trustguard.signals.parser.IngestionRequestParser;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;
import com.trustguard.signals.validation.IngestionRequestGuard;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IngestionConfigTest {

    private static final String PROPERTY = "trustguard.events.hmac-key";
    private static final int KEY_BYTES = 32;
    private static final int MAX_TEXT = 255;
    private static final String SECRET = "secret-value";
    private static final TenantId TENANT = new TenantId("123e4567-e89b-12d3-a456-426614174000");
    private static final ProjectId PROJECT = new ProjectId("223e4567-e89b-12d3-a456-426614174000");
    private static final EventId EVENT_ID = new EventId("323e4567-e89b-12d3-a456-426614174000");
    private static final Instant RECEIVED_AT = Instant.parse("2026-03-10T12:00:00Z");

    private static final Validator VALIDATOR = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory()
            .getValidator();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EventHmacConfig.class, IngestionConfig.class)
            .withBean(Validator.class, () -> VALIDATOR);

    private static byte[] key() {
        byte[] key = new byte[KEY_BYTES];
        for (int i = 0; i < KEY_BYTES; i++) {
            key[i] = (byte) (i + 1);
        }
        return key;
    }

    private static String base64Key() {
        return Base64.getEncoder().encodeToString(key());
    }

    private static byte[] validBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "USER");
        body.put("actorId", "user-42");
        body.put("occurredAt", "2026-03-10T11:59:00Z");
        body.put("sdkVersion", "1.0.0");
        body.put("environment", "PRODUCTION");
        body.put("ipAddress", "203.0.113.7");
        return JsonMapper.builder().build().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] invalidBodyWithSecret() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("actorType", "USER");
        body.put("actorId", "");
        body.put("sessionId", "");
        body.put("occurredAt", "2026-03-10T11:59:00Z");
        body.put("sdkVersion", SECRET + "a".repeat(MAX_TEXT));
        body.put("environment", "PRODUCTION");
        body.put("ipAddress", "a".repeat(46));
        body.put("projectId", "not-a-uuid");
        return JsonMapper.builder().build().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the mapper, parser, guard and both generators are single beans")
    void allIngestionBeansAreStarted() {
        runner.withPropertyValues(PROPERTY + "=" + base64Key()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(EventEnvelopeMapper.class);
            assertThat(context).hasSingleBean(IngestionRequestParser.class);
            assertThat(context).hasSingleBean(IngestionRequestGuard.class);
            assertThat(context).hasSingleBean(AnonymousActorIdGenerator.class);
            assertThat(context).hasSingleBean(IdempotencyKeyGenerator.class);
        });
    }

    @Test
    @DisplayName("no JSON mapper bean is created: the parser's mapper is private (Ruling 86)")
    void noObjectMapperBeanIsCreated() {
        runner.withPropertyValues(PROPERTY + "=" + base64Key()).run(context ->
                assertThat(context.getBeanNamesForType(ObjectMapper.class)).isEmpty());
    }

    @Test
    @DisplayName("the mapper bean is wired to the generators built from the key")
    void theMapperUsesTheConfiguredGenerators() {
        runner.withPropertyValues(PROPERTY + "=" + base64Key()).run(context -> {
            EventEnvelopeMapper mapper = context.getBean(EventEnvelopeMapper.class);
            LoginEventDto dto = (LoginEventDto) EventDtoFixture.builder()
                    .actorType(ActorType.ANONYMOUS).actorId(null).sessionId("session-abc").idempotencyKey(null)
                    .build(LoginEventDto.class);

            EventEnvelope envelope = mapper.map(dto, new IngestionContext(TENANT, PROJECT, EVENT_ID, RECEIVED_AT));

            assertThat(envelope.actorId())
                    .isEqualTo(new AnonymousActorIdGenerator(key()).generate(TENANT, "session-abc"));
            assertThat(envelope.idempotencyKey()).isEqualTo(new IdempotencyKeyGenerator(key())
                    .generate(TENANT, envelope.actorId(), EventType.LOGIN, dto.occurredAt(), envelope.payload()));
        });
    }

    @Test
    @DisplayName("the parser bean parses a valid body and rejects an invalid one")
    void theParserBeanWorks() {
        runner.withPropertyValues(PROPERTY + "=" + base64Key()).run(context -> {
            IngestionRequestParser parser = context.getBean(IngestionRequestParser.class);

            assertThat(parser.parse(validBody(), LoginEventDto.class).actorId()).isEqualTo("user-42");
            assertThatThrownBy(() -> parser.parse("{}".getBytes(StandardCharsets.UTF_8), LoginEventDto.class))
                    .isInstanceOf(InvalidIngestionRequestException.class);
        });
    }

    @Test
    @DisplayName("the parser works with Spring Boot's real validator and echoes no submitted text")
    void theParserWorksWithSpringsRealValidator() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(EventHmacConfig.class, IngestionConfig.class)
                .withPropertyValues(PROPERTY + "=" + base64Key())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    IngestionRequestParser parser = context.getBean(IngestionRequestParser.class);

                    assertThatThrownBy(() -> parser.parse(invalidBodyWithSecret(), LoginEventDto.class))
                            .isInstanceOfSatisfying(InvalidIngestionRequestException.class, thrown -> {
                                assertThat(thrown.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                                assertThat(thrown.details()).isNotEmpty();
                                assertThat(thrown.getMessage()).doesNotContain(SECRET);
                                assertThat(thrown.getCause()).isNull();
                                for (FieldDetail detail : thrown.details()) {
                                    assertThat(detail.field()).doesNotContain(SECRET);
                                    assertThat(detail.message()).doesNotContain(SECRET);
                                }
                            });
                });
    }

    @Test
    @DisplayName("a missing key still stops the application with the fixed message")
    void aMissingKeyStillStopsTheApplication() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context).getFailure().rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("EVENT_HMAC_KEY is missing or blank.");
        });
    }

    @Test
    @DisplayName("without a Validator bean the parser cannot be built")
    void aMissingValidatorStopsTheApplication() {
        new ApplicationContextRunner()
                .withUserConfiguration(EventHmacConfig.class, IngestionConfig.class)
                .withPropertyValues(PROPERTY + "=" + base64Key())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().isInstanceOf(NoSuchBeanDefinitionException.class);
                });
    }
}