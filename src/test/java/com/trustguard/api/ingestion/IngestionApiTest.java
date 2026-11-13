package com.trustguard.api.ingestion;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import com.trustguard.signals.SignalsRedisIntegrationTestBase;

import static com.trustguard.api.ingestion.IngestionTestSupport.JSON;
import static com.trustguard.api.ingestion.IngestionTestSupport.JSON_UTF8;
import static com.trustguard.api.ingestion.IngestionTestSupport.claimRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.error;
import static com.trustguard.api.ingestion.IngestionTestSupport.errorCode;
import static com.trustguard.api.ingestion.IngestionTestSupport.eventRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.json;
import static com.trustguard.api.ingestion.IngestionTestSupport.outboxRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.parse;
import static com.trustguard.api.ingestion.IngestionTestSupport.post;
import static com.trustguard.api.ingestion.IngestionTestSupport.postJson;
import static com.trustguard.api.ingestion.IngestionTestSupport.validBody;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010d AC 1, 11, 12, 13 and the RabbitMQ half of 14, over a real port with a real Redis. Every rejection also
 * proves that nothing was stored for the tenant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionApiTest extends SignalsRedisIntegrationTestBase {

    private static final String NUL_ESCAPE = "\\u0000";
    private static final String LONE_SURROGATE_ESCAPE = "\\ud800";
    private static final String PLACEHOLDER = "@@BAD@@";
    private static final String BLOCKING_CONSTRAINT = "zz_b010d_block_outbox";
    private static final int OVERSIZE_PAYLOAD_CHARS = 40_000;
    private static final int OVERSIZE_BODY_CHARS = 70_000;
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_PAYLOAD_TOO_LARGE = 413;
    private static final int HTTP_UNSUPPORTED_MEDIA_TYPE = 415;
    private static final int HTTP_INTERNAL_ERROR = 500;
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private Environment springEnvironment;

    private Seeded seeded;
    private String authorization;

    @BeforeEach
    void seedTenant() {
        seeded = seed();
        authorization = IngestionTestSupport.authorization(SUPERUSER, seeded.tenantId(), seeded.projectId(),
                "TRACK");
    }

    private HttpResponse<String> send(Map<String, Object> body) throws Exception {
        return postJson(port, body, authorization);
    }

    private void assertNothingStored() {
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).as("trust_events").isZero();
        assertThat(outboxRows(SUPERUSER, seeded.tenantId())).as("outbox_events").isZero();
        assertThat(claimRows(SUPERUSER, seeded.tenantId())).as("idempotency_keys").isZero();
    }

    private void assertRejected(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(errorCode(response)).isEqualTo(code);
        assertThat(response.headers().firstValue("Content-Type")).contains(JSON_UTF8);
        assertNothingStored();
    }

    // ---------- AC 1 and the RabbitMQ half of AC 14 ----------

    @Test
    @DisplayName("a valid login is 202 ACCEPTED with an event id, stores one event and one outbox row, "
            + "while RabbitMQ is unreachable")
    void aValidLoginIsAccepted() throws Exception {
        HttpResponse<String> response = send(validBody());

        assertThat(springEnvironment.getProperty("spring.rabbitmq.port")).isEqualTo("1");
        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED);
        assertThat(response.headers().firstValue("Content-Type")).contains(JSON_UTF8);
        Map<String, Object> body = parse(response);
        assertThat(body).containsOnlyKeys("status", "eventId");
        assertThat(body.get("status")).isEqualTo("ACCEPTED");
        String eventId = (String) body.get("eventId");
        assertThat(UUID.fromString(eventId).version()).isEqualTo(7);
        assertThat(countEvents(eventId)).isEqualTo(1);
        assertThat(countOutbox(eventId)).isEqualTo(1);
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);
        assertThat(claimRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);
        assertThat(SUPERUSER.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?::uuid",
                String.class, eventId)).isEqualTo("PENDING");
    }

    // ---------- AC 11: rejections ----------

    @Test
    @DisplayName("a malformed body, an unknown field and a wrong type are 400 VALIDATION_FAILED")
    void invalidBodiesAreRejected() throws Exception {
        Map<String, Object> unknownField = validBody();
        unknownField.put("unexpected", "x");
        Map<String, Object> wrongType = validBody();
        wrongType.put("actorId", 5);
        Map<String, String> bodies = Map.of("malformed", "{", "empty", "", "not an object", "[]",
                "unknown field", json(unknownField), "wrong type", json(wrongType));

        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            HttpResponse<String> response = post(port, JSON, entry.getValue(), authorization);

            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(HTTP_BAD_REQUEST);
            assertThat(errorCode(response)).as(entry.getKey()).isEqualTo(VALIDATION_FAILED);
        }
        assertNothingStored();
    }

    @Test
    @DisplayName("an event more than 5 minutes ahead is 400 TIMESTAMP_TOO_FAR_FUTURE")
    void aFutureEventIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("occurredAt", Instant.now().plus(10, ChronoUnit.MINUTES).toString());

        assertRejected(send(body), HTTP_BAD_REQUEST, "TIMESTAMP_TOO_FAR_FUTURE");
    }

    @Test
    @DisplayName("an event older than 7 days is 400 TIMESTAMP_TOO_OLD")
    void anOldEventIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("occurredAt", Instant.now().minus(8, ChronoUnit.DAYS).toString());

        assertRejected(send(body), HTTP_BAD_REQUEST, "TIMESTAMP_TOO_OLD");
    }

    @Test
    @DisplayName("an event for another environment than the key's is 401 ENVIRONMENT_MISMATCH")
    void anEnvironmentMismatchIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("environment", "DEVELOPMENT");

        assertRejected(send(body), HTTP_UNAUTHORIZED, "ENVIRONMENT_MISMATCH");
    }

    @Test
    @DisplayName("an ANONYMOUS event without a session id is 400 SESSION_ID_REQUIRED")
    void anAnonymousEventNeedsASession() throws Exception {
        Map<String, Object> body = validBody();
        body.put("actorType", "ANONYMOUS");
        body.remove("actorId");

        assertRejected(send(body), HTTP_BAD_REQUEST, "SESSION_ID_REQUIRED");
    }

    @Test
    @DisplayName("an oversize payload field is 400 PAYLOAD_TOO_LARGE, not 413")
    void anOversizePayloadFieldIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("payload", Map.of("k", "a".repeat(OVERSIZE_PAYLOAD_CHARS)));

        assertRejected(send(body), HTTP_BAD_REQUEST, "PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("a body over 64 KB is 413 PAYLOAD_TOO_LARGE")
    void anOversizeBodyIsRejected() throws Exception {
        String body = "{\"p\":\"" + "a".repeat(OVERSIZE_BODY_CHARS) + "\"}";

        assertRejected(post(port, JSON, body, authorization), HTTP_PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
    }

    @Test
    @DisplayName("a wrong Content-Type is 415 UNSUPPORTED_MEDIA_TYPE")
    void aWrongContentTypeIsRejected() throws Exception {
        assertRejected(post(port, "text/plain", json(validBody()), authorization), HTTP_UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("no key and an invalid key are 401 INVALID_API_KEY")
    void authenticationIsRequired() throws Exception {
        String invalidKey = "Bearer sk_live_" + "a".repeat(32) + "_" + "b".repeat(64);

        assertRejected(post(port, JSON, json(validBody()), null), HTTP_UNAUTHORIZED, "INVALID_API_KEY");
        assertRejected(post(port, JSON, json(validBody()), invalidKey), HTTP_UNAUTHORIZED, "INVALID_API_KEY");
    }

    @Test
    @DisplayName("a CHECK-only key is 403 CAPABILITY_INSUFFICIENT")
    void aCheckOnlyKeyIsForbidden() throws Exception {
        String checkOnly = IngestionTestSupport.authorization(SUPERUSER, seeded.tenantId(), seeded.projectId(),
                "CHECK");

        assertRejected(postJson(port, validBody(), checkOnly), HTTP_FORBIDDEN, "CAPABILITY_INSUFFICIENT");
    }

    // ---------- Ruling 109: the project ----------

    @Test
    @DisplayName("a body projectId of another project is 400 with the detail projectId, and nothing is stored")
    void anotherProjectIsRejected() throws Exception {
        Map<String, Object> body = validBody();
        body.put("projectId", UUID.randomUUID().toString());

        HttpResponse<String> response = send(body);

        assertRejected(response, HTTP_BAD_REQUEST, VALIDATION_FAILED);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) error(response).get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0).get("field")).isEqualTo("projectId");
        assertThat(details.get(0).get("rejected")).isEqualTo("[REDACTED]");
    }

    @Test
    @DisplayName("the key's own project is accepted, in lowercase or uppercase, and is stored on the event")
    void theKeysOwnProjectIsAccepted() throws Exception {
        Map<String, Object> lower = validBody();
        lower.put("projectId", seeded.projectId().value());
        Map<String, Object> upper = validBody();
        upper.put("projectId", seeded.projectId().value().toUpperCase(java.util.Locale.ROOT));
        upper.put("actorId", "user-43");

        HttpResponse<String> first = send(lower);
        HttpResponse<String> second = send(upper);

        assertThat(first.statusCode()).isEqualTo(HTTP_ACCEPTED);
        assertThat(second.statusCode()).isEqualTo(HTTP_ACCEPTED);
        String eventId = (String) parse(first).get("eventId");
        assertThat(SUPERUSER.queryForObject("SELECT project_id::text FROM trust_events WHERE event_id = ?::uuid",
                String.class, eventId)).isEqualTo(seeded.projectId().value());
    }

    @Test
    @DisplayName("without a body projectId the event belongs to the key's project")
    void theDefaultProjectIsTheKeysProject() throws Exception {
        HttpResponse<String> response = send(validBody());

        String eventId = (String) parse(response).get("eventId");
        assertThat(SUPERUSER.queryForObject("SELECT project_id::text FROM trust_events WHERE event_id = ?::uuid",
                String.class, eventId)).isEqualTo(seeded.projectId().value());
    }

    // ---------- AC 12: text hygiene ----------

    @ParameterizedTest
    @ValueSource(strings = {"actorId", "sessionId", "sdkVersion", "ipAddress", "userAgent", "deviceFingerprint",
            "idempotencyKey"})
    @DisplayName("a text field with NUL or a lone surrogate is 400, and nothing is stored")
    void textFieldsAreCheckedByTheParser(String field) throws Exception {
        for (String bad : List.of(NUL_ESCAPE, LONE_SURROGATE_ESCAPE)) {
            Map<String, Object> body = validBody();
            body.put(field, PLACEHOLDER);
            String text = json(body).replace(PLACEHOLDER, "a" + bad + "b");

            HttpResponse<String> response = post(port, JSON, text, authorization);

            assertThat(response.statusCode()).as(field + " " + bad).isEqualTo(HTTP_BAD_REQUEST);
            assertThat(errorCode(response)).as(field + " " + bad).isEqualTo(VALIDATION_FAILED);
        }
        assertNothingStored();
    }

    @Test
    @DisplayName("a payload key or value, at any depth, with NUL or a lone surrogate is 400, and nothing is stored")
    void payloadTextIsCheckedByTheParser() throws Exception {
        List<Map<String, Object>> payloads = List.of(
                Map.of("k" + PLACEHOLDER, "v"),
                Map.of("k", "v" + PLACEHOLDER),
                Map.of("n", Map.of("k" + PLACEHOLDER, "v")),
                Map.of("n", Map.of("k", "v" + PLACEHOLDER)),
                Map.of("l", List.of("v" + PLACEHOLDER)));

        for (Map<String, Object> payload : payloads) {
            for (String bad : List.of(NUL_ESCAPE, LONE_SURROGATE_ESCAPE)) {
                Map<String, Object> body = validBody();
                body.put("payload", payload);
                String text = json(body).replace(PLACEHOLDER, "a" + bad + "b");

                HttpResponse<String> response = post(port, JSON, text, authorization);

                assertThat(response.statusCode()).as(text).isEqualTo(HTTP_BAD_REQUEST);
                assertThat(errorCode(response)).as(text).isEqualTo(VALIDATION_FAILED);
            }
        }
        assertNothingStored();
    }

    // ---------- AC 13: a persistence failure ----------

    @Test
    @DisplayName("a database failure is 500 INTERNAL_ERROR with fixed text, and nothing is stored")
    void aPersistenceFailureIs500() throws Exception {
        SUPERUSER.execute("ALTER TABLE outbox_events ADD CONSTRAINT " + BLOCKING_CONSTRAINT
                + " CHECK (false) NOT VALID");
        HttpResponse<String> response;
        try {
            response = send(validBody());
        } finally {
            SUPERUSER.execute("ALTER TABLE outbox_events DROP CONSTRAINT " + BLOCKING_CONSTRAINT);
        }

        assertThat(response.statusCode()).isEqualTo(HTTP_INTERNAL_ERROR);
        assertThat(response.headers().firstValue("Content-Type")).contains(JSON_UTF8);
        assertThat(errorCode(response)).isEqualTo("INTERNAL_ERROR");
        assertThat(error(response)).containsEntry("message", "The event could not be stored.")
                .containsEntry("retryable", false);
        assertThat(response.body().getBytes(StandardCharsets.UTF_8)).asString(StandardCharsets.UTF_8)
                .doesNotContain(BLOCKING_CONSTRAINT).doesNotContain("outbox_events");
        assertNothingStored();
    }
}