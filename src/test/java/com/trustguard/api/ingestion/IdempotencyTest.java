package com.trustguard.api.ingestion;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.trustguard.signals.SignalsRedisIntegrationTestBase;
import com.trustguard.signals.constants.IdempotencyConstants;
import com.trustguard.signals.service.IngestionCoordinator;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static com.trustguard.api.ingestion.IngestionTestSupport.claimRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.eventRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.outboxRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.parse;
import static com.trustguard.api.ingestion.IngestionTestSupport.postJson;
import static com.trustguard.api.ingestion.IngestionTestSupport.validBody;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010d AC 3, 4, 7, 8, 9 and 15 over a real port, a real Redis and the real database, plus the TTL alignment of
 * Ruling 121. The cross-tenant cases (AC 10) are in CrossTenantLeakTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyTest extends SignalsRedisIntegrationTestBase {

    private static final int THREADS = 8;
    private static final long WAIT_SECONDS = 60;
    private static final long MIN_TTL_SECONDS = 86_000;
    private static final long MAX_TTL_SECONDS = 86_400;
    private static final double MAX_TTL_DRIFT_SECONDS = 3.0;
    private static final String FOREIGN_EVENT_ID = "01900000-0000-7000-8000-000000000042";
    private static final String BLOCKING_CONSTRAINT = "zz_b010d_block_outbox_idem";
    private static final String MESSAGE_DUPLICATE = "Event already processed";
    private static final int HTTP_ACCEPTED = 202;
    private static final int HTTP_INTERNAL_ERROR = 500;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    private Seeded seeded;
    private String authorization;
    private String key;
    private Logger coordinatorLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void seedTenant() {
        seeded = seed();
        authorization = IngestionTestSupport.authorization(SUPERUSER, seeded.tenantId(), seeded.projectId(),
                "TRACK");
        key = "idem-" + java.util.UUID.randomUUID();
        coordinatorLogger = (Logger) LoggerFactory.getLogger(IngestionCoordinator.class);
        appender = new ListAppender<>();
        appender.start();
        coordinatorLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        coordinatorLogger.detachAppender(appender);
    }

    private String redisKey() {
        return seeded.tenantId().value() + IdempotencyConstants.REDIS_KEY_SEGMENT + key;
    }

    private Map<String, Object> bodyWithKey() {
        Map<String, Object> body = validBody();
        body.put("idempotencyKey", key);
        return body;
    }

    private HttpResponse<String> send(Map<String, Object> body) throws Exception {
        return postJson(port, body, authorization);
    }

    private String accepted(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED);
        Map<String, Object> body = parse(response);
        assertThat(body.get("status")).isEqualTo("ACCEPTED");
        return (String) body.get("eventId");
    }

    private void assertDuplicate(HttpResponse<String> response, String originalEventId) {
        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED);
        Map<String, Object> body = parse(response);
        assertThat(body).containsOnlyKeys("status", "originalEventId", "message");
        assertThat(body.get("status")).isEqualTo("DUPLICATE");
        assertThat(body.get("originalEventId")).isEqualTo(originalEventId);
        assertThat(body.get("message")).isEqualTo(MESSAGE_DUPLICATE);
    }

    private void assertRowCounts(int expected) {
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).as("trust_events").isEqualTo(expected);
        assertThat(outboxRows(SUPERUSER, seeded.tenantId())).as("outbox_events").isEqualTo(expected);
        assertThat(claimRows(SUPERUSER, seeded.tenantId())).as("idempotency_keys").isEqualTo(expected);
    }

    // ---------- AC 3 and 4, Ruling 121 ----------

    @Test
    @DisplayName("a repeated key is a DUPLICATE with the first event id, stores nothing, and Redis holds the key "
            + "for the remaining lifetime of the PostgreSQL row")
    void aRepeatedKeyIsADuplicate() throws Exception {
        String first = accepted(send(bodyWithKey()));

        assertThat(redis.opsForValue().get(redisKey())).isEqualTo(first);
        Long redisTtl = redis.getExpire(redisKey());
        assertThat(redisTtl).isBetween(MIN_TTL_SECONDS, MAX_TTL_SECONDS);
        Double databaseRemaining = SUPERUSER.queryForObject("SELECT EXTRACT(EPOCH FROM (expires_at - now()))::float8 "
                        + "FROM idempotency_keys WHERE tenant_id = ?::uuid AND idempotency_key = ?", Double.class,
                seeded.tenantId().value(), key);
        assertThat(Math.abs(redisTtl - databaseRemaining)).isLessThanOrEqualTo(MAX_TTL_DRIFT_SECONDS);

        HttpResponse<String> second = send(bodyWithKey());

        assertDuplicate(second, first);
        assertRowCounts(1);
    }

    @Test
    @DisplayName("when Redis has no key, the duplicate is found in PostgreSQL and Redis is not written back")
    void aDuplicateIsFoundInPostgres() throws Exception {
        String first = accepted(send(bodyWithKey()));
        redis.delete(redisKey());

        HttpResponse<String> second = send(bodyWithKey());

        assertDuplicate(second, first);
        assertThat(redis.hasKey(redisKey())).isFalse();
        assertRowCounts(1);
    }

    @Test
    @DisplayName("a Redis value that is not an event id is ignored and PostgreSQL answers")
    void aPoisonedRedisValueIsIgnored() throws Exception {
        String first = accepted(send(bodyWithKey()));
        redis.opsForValue().set(redisKey(), "not-an-event-id");

        HttpResponse<String> second = send(bodyWithKey());

        assertDuplicate(second, first);
        assertRowCounts(1);
    }

    @Test
    @DisplayName("a key found in Redis is answered from Redis: nothing is stored, not even a claim")
    void aRedisHitIsAnsweredFromRedis() throws Exception {
        redis.opsForValue().set(redisKey(), FOREIGN_EVENT_ID);

        HttpResponse<String> response = send(bodyWithKey());

        assertDuplicate(response, FOREIGN_EVENT_ID);
        assertRowCounts(0);
    }

    // ---------- AC 8 ----------

    @Test
    @DisplayName("8 concurrent requests with one key give one ACCEPTED, seven DUPLICATE, one event and one outbox row")
    void concurrentRequestsAreDeduplicated() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return send(bodyWithKey());
                }));
            }
            start.countDown();
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }

            List<Map<String, Object>> bodies = responses.stream().map(IngestionTestSupport::parse).toList();
            assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isEqualTo(HTTP_ACCEPTED));
            List<Map<String, Object>> winners = bodies.stream().filter(b -> "ACCEPTED".equals(b.get("status")))
                    .toList();
            List<Map<String, Object>> duplicates = bodies.stream().filter(b -> "DUPLICATE".equals(b.get("status")))
                    .toList();
            assertThat(winners).hasSize(1);
            assertThat(duplicates).hasSize(THREADS - 1);
            String winner = (String) winners.get(0).get("eventId");
            assertThat(duplicates).allSatisfy(b -> assertThat(b.get("originalEventId")).isEqualTo(winner));
            assertRowCounts(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- AC 9 ----------

    @Test
    @DisplayName("without an idempotencyKey the identical body twice is a DUPLICATE; another occurredAt or "
            + "payload is a new event")
    void theFallbackKeyDeduplicates() throws Exception {
        Map<String, Object> body = validBody();
        String first = accepted(send(body));

        assertDuplicate(send(body), first);
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);

        Map<String, Object> otherTime = validBody();
        otherTime.put("occurredAt", Instant.parse((String) body.get("occurredAt")).minus(1, ChronoUnit.SECONDS)
                .toString());
        Map<String, Object> otherPayload = validBody();
        otherPayload.put("occurredAt", body.get("occurredAt"));
        otherPayload.put("payload", Map.of("k", "different"));

        String second = accepted(send(otherTime));
        String third = accepted(send(otherPayload));

        assertThat(List.of(first, second, third)).doesNotHaveDuplicates();
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).isEqualTo(3);
        assertThat(outboxRows(SUPERUSER, seeded.tenantId())).isEqualTo(3);
    }

    // ---------- AC 7 ----------

    @Test
    @DisplayName("a failed store removes the claim, writes no Redis key, and the identical retry is ACCEPTED")
    void aFailedStoreRemovesTheClaim() throws Exception {
        SUPERUSER.execute("ALTER TABLE outbox_events ADD CONSTRAINT " + BLOCKING_CONSTRAINT
                + " CHECK (false) NOT VALID");
        HttpResponse<String> failed;
        try {
            failed = send(bodyWithKey());
        } finally {
            SUPERUSER.execute("ALTER TABLE outbox_events DROP CONSTRAINT " + BLOCKING_CONSTRAINT);
        }

        assertThat(failed.statusCode()).isEqualTo(HTTP_INTERNAL_ERROR);
        assertRowCounts(0);
        assertThat(redis.hasKey(redisKey())).isFalse();

        String retried = accepted(send(bodyWithKey()));

        assertRowCounts(1);
        assertThat(redis.opsForValue().get(redisKey())).isEqualTo(retried);
    }

    // ---------- AC 15 ----------

    @Test
    @DisplayName("a duplicate is logged once at INFO with the tenant and both event ids, and no client text")
    void aDuplicateIsLoggedOnce() throws Exception {
        String first = accepted(send(bodyWithKey()));
        appender.list.clear();

        assertDuplicate(send(bodyWithKey()), first);

        List<ILoggingEvent> infos = appender.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infos).hasSize(1);
        String message = infos.get(0).getFormattedMessage();
        assertThat(message).contains(seeded.tenantId().value()).contains(first)
                .doesNotContain(key).doesNotContain("user-42").doesNotContain("203.0.113.7");
        assertThat(message.split("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", -1))
                .hasSize(4);
    }
}