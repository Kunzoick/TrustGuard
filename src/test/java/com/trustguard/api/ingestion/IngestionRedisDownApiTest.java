package com.trustguard.api.ingestion;

import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import com.trustguard.signals.SignalsIntegrationTestBase;

import static com.trustguard.api.ingestion.IngestionTestSupport.claimRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.eventRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.outboxRows;
import static com.trustguard.api.ingestion.IngestionTestSupport.parse;
import static com.trustguard.api.ingestion.IngestionTestSupport.postJson;
import static com.trustguard.api.ingestion.IngestionTestSupport.validBody;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-010d AC 14, the Redis half: with Redis and RabbitMQ both unreachable the request is still accepted, and the
 * replay is a DUPLICATE found through PostgreSQL (Ruling 112). Authentication itself falls back to PostgreSQL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IngestionRedisDownApiTest extends SignalsIntegrationTestBase {

    private static final int HTTP_ACCEPTED = 202;

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private Environment springEnvironment;
    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("authResolverJdbcTemplate")
    private org.springframework.jdbc.core.JdbcTemplate authResolver;

    @Test
    @DisplayName("with Redis and RabbitMQ unreachable a login is ACCEPTED and its replay is a DUPLICATE")
    void ingestionSurvivesAnUnreachableRedis() throws Exception {
        Seeded seeded = seed();
        String authorization = IngestionTestSupport.authorization(SUPERUSER, seeded.tenantId(),
                seeded.projectId(), "TRACK");
        Map<String, Object> body = validBody();
        body.put("idempotencyKey", "redis-down-key");


        HttpResponse<String> first = postJson(port, body, authorization);
        HttpResponse<String> second = postJson(port, body, authorization);

        assertThat(springEnvironment.getProperty("spring.data.redis.port")).isEqualTo("1");
        assertThat(springEnvironment.getProperty("spring.rabbitmq.port")).isEqualTo("1");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(HTTP_ACCEPTED);
        Map<String, Object> firstBody = parse(first);
        assertThat(firstBody.get("status")).isEqualTo("ACCEPTED");
        assertThat(second.statusCode()).isEqualTo(HTTP_ACCEPTED);
        Map<String, Object> secondBody = parse(second);
        assertThat(secondBody.get("status")).isEqualTo("DUPLICATE");
        assertThat(secondBody.get("originalEventId")).isEqualTo(firstBody.get("eventId"));
        assertThat(eventRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);
        assertThat(outboxRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);
        assertThat(claimRows(SUPERUSER, seeded.tenantId())).isEqualTo(1);
    }
}