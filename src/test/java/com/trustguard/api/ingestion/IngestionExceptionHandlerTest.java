package com.trustguard.api.ingestion;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.trustguard.shared.enums.ErrorCode;
import com.trustguard.shared.error.EnvironmentMismatchException;
import com.trustguard.shared.error.InvalidEventPayloadException;
import com.trustguard.shared.error.InvalidIngestionRequestException;
import com.trustguard.shared.error.InvalidIngestionRequestException.FieldDetail;
import com.trustguard.shared.error.SessionIdRequiredException;

import jakarta.servlet.ServletException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IngestionExceptionHandlerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private static final String SECRET_TEXT = "secret internal text";
    private static final String MESSAGE_SESSION = "sessionId is required for ANONYMOUS actors.";
    private static final String MESSAGE_PAYLOAD = "Event payload cannot be processed.";
    private static final String MESSAGE_ENVIRONMENT = "Event environment does not match the API key environment.";

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new IngestionExceptionHandler())
            .build();

    /** Test-only controller in the handler's package, so the advice applies to it. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/invalid")
        String invalid() {
            throw new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, "Request validation failed.",
                    List.of(new FieldDetail("sdkVersion", "size must be between 1 and 255"),
                            new FieldDetail("ipAddress", "must not be blank")));
        }

        @GetMapping("/probe/plain")
        String plain() {
            throw new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, "Request body is not a valid event.");
        }

        @GetMapping("/probe/too-large")
        String tooLarge() {
            throw new InvalidIngestionRequestException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Event payload exceeds the maximum allowed size.",
                    List.of(new FieldDetail("payload", "payload exceeds the maximum allowed size")));
        }

        @GetMapping("/probe/timestamp")
        String timestamp() {
            throw new InvalidIngestionRequestException(ErrorCode.TIMESTAMP_TOO_OLD,
                    "occurredAt is older than the accepted window.",
                    List.of(new FieldDetail("occurredAt", "occurredAt is older than the accepted window.")));
        }

        @GetMapping("/probe/environment")
        String environment() {
            throw new EnvironmentMismatchException(SECRET_TEXT);
        }

        @GetMapping("/probe/session")
        String session() {
            throw new SessionIdRequiredException(SECRET_TEXT);
        }

        @GetMapping("/probe/payload")
        String payload() {
            throw new InvalidEventPayloadException(SECRET_TEXT);
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException(SECRET_TEXT);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(String path) throws Exception {
        String text = mockMvc.perform(get(path)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return (Map<String, Object>) MAPPER.readValue(text, Object.class);
    }

    // ---------- InvalidIngestionRequestException ----------

    @Test
    @DisplayName("an invalid request becomes 400 with its code, fixed message and every detail, values redacted")
    void invalidRequestIsMappedWithDetails() throws Exception {
        mockMvc.perform(get("/probe/invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(JSON_UTF8))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("Request validation failed."))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.details.length()").value(2))
                .andExpect(jsonPath("$.error.details[0].field").value("sdkVersion"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"))
                .andExpect(jsonPath("$.error.details[0].message").value("size must be between 1 and 255"))
                .andExpect(jsonPath("$.error.details[1].field").value("ipAddress"));
    }

    @Test
    @DisplayName("an invalid request without details has no details key at all")
    void noDetailsMeansNoDetailsKey() throws Exception {
        mockMvc.perform(get("/probe/plain"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("PAYLOAD_TOO_LARGE and the timestamp codes are 400 and keep their own code")
    void otherInvalidRequestCodesAreBadRequests() throws Exception {
        mockMvc.perform(get("/probe/too-large"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.error.details[0].field").value("payload"));
        mockMvc.perform(get("/probe/timestamp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TIMESTAMP_TOO_OLD"))
                .andExpect(jsonPath("$.error.details[0].field").value("occurredAt"));
    }

    // ---------- other exceptions ----------

    @Test
    @DisplayName("an environment mismatch is 401 ENVIRONMENT_MISMATCH with the handler's own text")
    void environmentMismatchIs401() throws Exception {
        mockMvc.perform(get("/probe/environment"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(JSON_UTF8))
                .andExpect(jsonPath("$.error.code").value("ENVIRONMENT_MISMATCH"))
                .andExpect(jsonPath("$.error.message").value(MESSAGE_ENVIRONMENT))
                .andExpect(jsonPath("$.error.retryable").value(false))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @Test
    @DisplayName("a missing session id is 400 SESSION_ID_REQUIRED with the handler's own text")
    void missingSessionIdIs400() throws Exception {
        mockMvc.perform(get("/probe/session"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SESSION_ID_REQUIRED"))
                .andExpect(jsonPath("$.error.message").value(MESSAGE_SESSION));
    }

    @Test
    @DisplayName("an unusable payload is 400 VALIDATION_FAILED with the handler's own text")
    void unusablePayloadIs400() throws Exception {
        mockMvc.perform(get("/probe/payload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value(MESSAGE_PAYLOAD));
    }

    @Test
    @DisplayName("no body ever carries the text of the exception that caused it")
    void exceptionTextIsNeverEchoed() throws Exception {
        for (String path : List.of("/probe/environment", "/probe/session", "/probe/payload")) {
            String text = mockMvc.perform(get(path)).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);

            assertThat(text).as(path).doesNotContain("secret");
        }
    }

    @Test
    @DisplayName("every body has exactly the error object with code, message and retryable")
    @SuppressWarnings("unchecked")
    void bodyShape() throws Exception {
        Map<String, Object> plain = body("/probe/plain");
        Map<String, Object> detailed = body("/probe/invalid");

        assertThat(plain).containsOnlyKeys("error");
        assertThat((Map<String, Object>) plain.get("error")).containsOnlyKeys("code", "message", "retryable");
        assertThat((Map<String, Object>) detailed.get("error"))
                .containsOnlyKeys("code", "message", "retryable", "details");
    }

    @Test
    @DisplayName("there is no catch-all: an unhandled exception is not turned into a body")
    void unhandledExceptionsAreNotHandled() {
        assertThatThrownBy(() -> mockMvc.perform(get("/probe/boom")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the advice is scoped to the ingestion package and handles no broad exception type")
    void adviceIsScopedAndHasNoCatchAll() {
        RestControllerAdvice advice = IngestionExceptionHandler.class.getAnnotation(RestControllerAdvice.class);
        List<Class<?>> handled = new ArrayList<>();
        for (Method method : IngestionExceptionHandler.class.getDeclaredMethods()) {
            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation != null) {
                handled.addAll(Arrays.asList(annotation.value()));
            }
        }

        assertThat(advice.basePackages()).containsExactly("com.trustguard.api.ingestion");
        assertThat(handled).containsExactlyInAnyOrder(InvalidIngestionRequestException.class,
                EnvironmentMismatchException.class, SessionIdRequiredException.class,
                InvalidEventPayloadException.class);
        assertThat(handled).doesNotContain(Exception.class, RuntimeException.class, Throwable.class);
    }

    // ---------- the exception itself ----------

    @Test
    @DisplayName("the exception carries its code, is not retryable and has no cause")
    void exceptionCarriesCodeAndIsNotRetryable() {
        InvalidIngestionRequestException exception =
                new InvalidIngestionRequestException(ErrorCode.TIMESTAMP_TOO_OLD, "fixed");

        assertThat(exception.code()).isEqualTo(ErrorCode.TIMESTAMP_TOO_OLD);
        assertThat(exception.retryable()).isFalse();
        assertThat(exception.getMessage()).isEqualTo("fixed");
        assertThat(exception.getCause()).isNull();
        assertThat(exception.details()).isEmpty();
    }

    @Test
    @DisplayName("the details are copied on the way in and cannot be changed on the way out")
    void detailsAreImmutable() {
        List<FieldDetail> source = new ArrayList<>(List.of(new FieldDetail("a", "b")));
        InvalidIngestionRequestException exception =
                new InvalidIngestionRequestException(ErrorCode.VALIDATION_FAILED, "fixed", source);

        source.add(new FieldDetail("c", "d"));

        assertThat(exception.details()).containsExactly(new FieldDetail("a", "b"));
        assertThatThrownBy(() -> exception.details().add(new FieldDetail("x", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a field detail needs a field and a message")
    void fieldDetailRejectsNulls() {
        assertThatThrownBy(() -> new FieldDetail(null, "m")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FieldDetail("f", null)).isInstanceOf(NullPointerException.class);
    }
}