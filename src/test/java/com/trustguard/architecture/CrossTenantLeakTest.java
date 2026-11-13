package com.trustguard.architecture;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.api.ingestion.IngestionTestSupport;
import com.trustguard.signals.SignalsRedisIntegrationTestBase;
import com.trustguard.signals.constants.IdempotencyConstants;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 4.2's three cross-tenant leak vectors. Each is a P0 test (Rule 18.5). Vectors 1 and 2 are real since B-010d
 * (Rulings 34 and 122): both run over real HTTP with the database role the application uses. Vector 3 is still a
 * skeleton, @Disabled with a reason, and costs nothing: a skipped method does not start the Spring context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CrossTenantLeakTest extends SignalsRedisIntegrationTestBase {

    private static final int HTTP_ACCEPTED = 202;
    private static final String COUNT_EVENTS = "SELECT count(*) FROM trust_events WHERE event_id = ?::uuid";
    private static final String COUNT_OUTBOX = "SELECT count(*) FROM outbox_events WHERE event_id = ?::uuid";
    private static final String COUNT_CLAIMS = "SELECT count(*) FROM idempotency_keys WHERE idempotency_key = ?";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private StringRedisTemplate redis;

    /** Counts rows as the application role inside the tenant's own transaction, with no tenant predicate. */
    private int countAs(Seeded tenant, String sql, String argument) {
        return callInTenant(tenant.tenantId(),
                () -> new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, argument));
    }

    @Test
    void queryLayerLeak_tenantAQueryAsTenantB_returnsEmpty() throws Exception {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        String authA = IngestionTestSupport.authorization(SUPERUSER, tenantA.tenantId(), tenantA.projectId(),
                "TRACK");
        String key = "vector-one-key";
        Map<String, Object> body = IngestionTestSupport.validBody();
        body.put("idempotencyKey", key);

        HttpResponse<String> response = IngestionTestSupport.postJson(port, body, authA);

        assertThat(response.statusCode()).isEqualTo(HTTP_ACCEPTED);
        String eventId = (String) IngestionTestSupport.parse(response).get("eventId");
        assertThat(countAs(tenantB, COUNT_EVENTS, eventId)).as("tenant B trust_events").isZero();
        assertThat(countAs(tenantB, COUNT_OUTBOX, eventId)).as("tenant B outbox_events").isZero();
        assertThat(countAs(tenantB, COUNT_CLAIMS, key)).as("tenant B idempotency_keys").isZero();
        assertThat(countAs(tenantA, COUNT_EVENTS, eventId)).as("tenant A trust_events").isEqualTo(1);
        assertThat(countAs(tenantA, COUNT_OUTBOX, eventId)).as("tenant A outbox_events").isEqualTo(1);
        assertThat(countAs(tenantA, COUNT_CLAIMS, key)).as("tenant A idempotency_keys").isEqualTo(1);
    }

    @Test
    void cacheLayerLeak_tenantAIdempotencyKey_notVisibleToTenantB() throws Exception {
        Seeded tenantA = seed();
        Seeded tenantB = seed();
        String authA = IngestionTestSupport.authorization(SUPERUSER, tenantA.tenantId(), tenantA.projectId(),
                "TRACK");
        String authB = IngestionTestSupport.authorization(SUPERUSER, tenantB.tenantId(), tenantB.projectId(),
                "TRACK");
        String sharedKey = "shared-idempotency-key";
        Map<String, Object> body = IngestionTestSupport.validBody();
        body.put("idempotencyKey", sharedKey);

        HttpResponse<String> responseA = IngestionTestSupport.postJson(port, body, authA);
        HttpResponse<String> responseB = IngestionTestSupport.postJson(port, body, authB);

        assertThat(responseA.statusCode()).isEqualTo(HTTP_ACCEPTED);
        assertThat(responseB.statusCode()).isEqualTo(HTTP_ACCEPTED);
        Map<String, Object> parsedA = IngestionTestSupport.parse(responseA);
        Map<String, Object> parsedB = IngestionTestSupport.parse(responseB);
        assertThat(parsedA.get("status")).isEqualTo("ACCEPTED");
        assertThat(parsedB.get("status")).isEqualTo("ACCEPTED");
        String eventA = (String) parsedA.get("eventId");
        String eventB = (String) parsedB.get("eventId");
        assertThat(eventA).isNotEqualTo(eventB);
        assertThat(responseA.body()).doesNotContain(eventB);
        assertThat(responseB.body()).doesNotContain(eventA);

        String redisKeyA = tenantA.tenantId().value() + IdempotencyConstants.REDIS_KEY_SEGMENT + sharedKey;
        String redisKeyB = tenantB.tenantId().value() + IdempotencyConstants.REDIS_KEY_SEGMENT + sharedKey;
        assertThat(redis.opsForValue().get(redisKeyA)).isEqualTo(eventA);
        assertThat(redis.opsForValue().get(redisKeyB)).isEqualTo(eventB);
        assertThat(redis.hasKey(IdempotencyConstants.REDIS_KEY_SEGMENT + sharedKey)).isFalse();
        assertThat(redis.hasKey(sharedKey)).isFalse();

        Map<String, Object> replayA = IngestionTestSupport.parse(IngestionTestSupport.postJson(port, body, authA));
        Map<String, Object> replayB = IngestionTestSupport.parse(IngestionTestSupport.postJson(port, body, authB));
        assertThat(replayA.get("status")).isEqualTo("DUPLICATE");
        assertThat(replayA.get("originalEventId")).isEqualTo(eventA);
        assertThat(replayB.get("status")).isEqualTo("DUPLICATE");
        assertThat(replayB.get("originalEventId")).isEqualTo(eventB);
        assertThat(IngestionTestSupport.eventRows(SUPERUSER, tenantA.tenantId())).isEqualTo(1);
        assertThat(IngestionTestSupport.eventRows(SUPERUSER, tenantB.tenantId())).isEqualTo(1);
    }

    @Test
    @Disabled("Vector 3 (aggregate leak) needs an admin endpoint that returns tenant data. Owner: B-022 (Tenant "
            + "Self-Service API) or the first admin endpoint that returns tenant data (Ruling 122).")
    void aggregateLeak_adminEndpointAsTenantA_excludesTenantBData() {
        // Rule 4.2 Vector 3: create data for both tenants, call an admin endpoint authenticated as Tenant A,
        // assert no Tenant B data appears.
    }
}