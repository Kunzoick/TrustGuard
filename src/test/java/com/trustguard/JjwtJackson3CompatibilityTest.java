package com.trustguard;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.trustguard.sdk.ProbeControllerTestConfig;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B-007 Step 0 spike. jjwt-jackson uses its own Jackson 2 (com.fasterxml)
 * internally while Spring Boot 4 uses Jackson 3 (tools.jackson). These tests
 * prove, at runtime and not just at compile time, that both can coexist:
 * (b) jjwt signs and parses correctly, and (c) the Spring context and
 * Jackson 3 serialization are unaffected. Kept as a permanent regression test.
 */
class JjwtJackson3CompatibilityTest {

    private static final int KEY_BYTES = 32;
    private static final Duration TOKEN_TTL = Duration.ofMinutes(60);
    private static final String TEST_ADMIN_ID = "11111111-2222-3333-4444-555555555555";

    private static SecretKey randomKey() {
        byte[] bytes = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Keys.hmacShaKeyFor(bytes);
    }

    private static String signedToken(SecretKey key) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(TEST_ADMIN_ID)
                .claim("adminId", TEST_ADMIN_ID)
                .claim("tokenVersion", 3)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key)
                .compact();
    }

    @Test
    void signed_hs256_token_round_trips_subject_and_custom_claims() {
        SecretKey key = randomKey();

        Jws<Claims> parsed = Jwts.parser().verifyWith(key).build().parseSignedClaims(signedToken(key));

        assertEquals("HS256", parsed.getHeader().getAlgorithm());
        assertEquals(TEST_ADMIN_ID, parsed.getPayload().getSubject());
        assertEquals(TEST_ADMIN_ID, parsed.getPayload().get("adminId", String.class));
        assertEquals(3, parsed.getPayload().get("tokenVersion", Integer.class));
        assertEquals(true, parsed.getPayload().getExpiration().after(parsed.getPayload().getIssuedAt()));
    }

    @Test
    void token_signed_with_a_different_key_is_rejected() {
        String token = signedToken(randomKey());
        SecretKey otherKey = randomKey();

        assertThrows(JwtException.class,
                () -> Jwts.parser().verifyWith(otherKey).build().parseSignedClaims(token));
    }

    @Test
    void unsigned_alg_none_token_is_rejected() {
        Instant now = Instant.now();
        String unsigned = Jwts.builder()
                .subject(TEST_ADMIN_ID)
                .claim("tokenVersion", 3)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .compact();
        SecretKey key = randomKey();

        assertThrows(JwtException.class,
                () -> Jwts.parser().verifyWith(key).build().parseSignedClaims(unsigned));
    }

    @Test
    void tampered_payload_is_rejected() {
        SecretKey key = randomKey();
        String[] parts = signedToken(key).split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"attacker\"}".getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThrows(JwtException.class,
                () -> Jwts.parser().verifyWith(key).build().parseSignedClaims(forged));
    }

    /**
     * Separate nested class so the plain tests above never start Docker.
     * Owns its own containers (static members in an inner class are legal
     * from Java 16).
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Testcontainers
    @Import(ProbeControllerTestConfig.class)
    class SpringContextCoexistenceTest {

        private static final String APP_PASSWORD = "test-only-not-for-production";
        private static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
        // 32 bytes, base64. Test-only value, never used in src/main.
        private static final String TEST_ADMIN_JWT_SECRET = "dGVzdC1vbmx5LWFkbWluLWp3dC1zZWNyZXQtMzItYnl0ZXM=";

        @Container
        static final PostgreSQLContainer<?> POSTGRES =
                new PostgreSQLContainer<>("postgres:16")
                        .withDatabaseName("trustguard_test")
                        .withUsername("trustguard")
                        .withPassword("trustguard");

        @Container
        static final GenericContainer<?> REDIS =
                new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                        .withExposedPorts(6379);

        @DynamicPropertySource
        static void registerProperties(DynamicPropertyRegistry registry) {
            registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
            registry.add("spring.flyway.user", POSTGRES::getUsername);
            registry.add("spring.flyway.password", POSTGRES::getPassword);
            registry.add("spring.flyway.placeholders.trustguardAppPassword", () -> APP_PASSWORD);
            registry.add("spring.flyway.placeholders.trustguardAuthResolverPassword", () -> APP_PASSWORD);

            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);

            registry.add("trustguard.auth-resolver.datasource.jdbc-url", POSTGRES::getJdbcUrl);
            registry.add("trustguard.auth-resolver.datasource.username", POSTGRES::getUsername);
            registry.add("trustguard.auth-resolver.datasource.password", POSTGRES::getPassword);
            registry.add("trustguard.auth-resolver.datasource.minimum-idle", () -> "2");
            registry.add("trustguard.auth-resolver.datasource.maximum-pool-size", () -> "5");
            registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-jjwt-spike-pool");

            registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
            registry.add("trustguard.admin.jwt-secret", () -> TEST_ADMIN_JWT_SECRET);
            registry.add("spring.jpa.properties.hibernate.dialect",
                    () -> "org.hibernate.dialect.PostgreSQLDialect");

            registry.add("spring.data.redis.host", REDIS::getHost);
            registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        }

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Test
        void context_loads_and_jackson3_object_mapper_serializes_with_jackson2_present() {
            String json = objectMapper.writeValueAsString(Map.of("ok", true));

            assertEquals("{\"ok\":true}", json);
        }

        @Test
        void unauthenticated_probe_request_returns_jackson3_written_401_json() throws Exception {
            mockMvc.perform(get("/api/v1/probe/ping"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));
        }
    }
}