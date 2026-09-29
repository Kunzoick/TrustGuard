package com.trustguard.sdk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.util.HexFormat;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CF-003 mandatory integration test — strict version per Agent 4
 * ruling (2026-08-30). Uses real KeyHashVerificationService, real
 * PostgreSQL (full V1-V7 Flyway stack, so trustguard_authresolver
 * and BYPASSRLS actually exist and are exercised), and real Redis
 * for the happy path. The revoked-key case runs in a nested class
 * with Redis deliberately unreachable, exercising the ADR-006
 * PostgreSQL fallback for real rather than by mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import(ProbeControllerTestConfig.class)
class ApiKeyAuthIntegrationTest {

    private static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
    private static final String APP_PASSWORD = "test-only-not-for-production";

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

    private static JdbcTemplate seedingJdbcTemplate;

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
        registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-auth-resolver-pool");

        registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @BeforeAll
    static void seedSetup() {
        DriverManagerDataSource superuserDataSource = new DriverManagerDataSource();
        superuserDataSource.setUrl(POSTGRES.getJdbcUrl());
        superuserDataSource.setUsername(POSTGRES.getUsername());
        superuserDataSource.setPassword(POSTGRES.getPassword());
        seedingJdbcTemplate = new JdbcTemplate(superuserDataSource);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void valid_key_correct_environment_track_capability_reaches_controller() throws Exception {
        String keyId = randomHex(32);
        String hmac = computeRealHmac(keyId);
        seedTenantProjectAndKey(keyId, "PRODUCTION", "TRACK", "CHECK");

        mockMvc.perform(get("/api/v1/probe/ping")
                        .header("Authorization", "Bearer sk_live_" + keyId + "_" + hmac))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true));
    }

    @Test
    void valid_key_wrong_environment_returns_401_environment_mismatch() throws Exception {
        String keyId = randomHex(32);
        String hmac = computeRealHmac(keyId);
        seedTenantProjectAndKey(keyId, "DEVELOPMENT", "TRACK");

        mockMvc.perform(get("/api/v1/probe/ping")
                        .header("Authorization", "Bearer sk_live_" + keyId + "_" + hmac))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ENVIRONMENT_MISMATCH"))
                .andExpect(content().string(not(containsString("pong"))));
    }

    @Test
    void invalid_hmac_returns_401_invalid_api_key_before_any_lookup() throws Exception {
        String keyId = randomHex(32);
        String realHmac = computeRealHmac(keyId);
        // Flip one character of a genuinely computed HMAC — produces
        // a syntactically valid 64-hex-character string (passes
        // parseKey()'s format validation) that fails
        // MessageDigest.isEqual() at step 4. No api_keys row is
        // seeded — HMAC verification happens purely from the server
        // secret and keyId, with no database lookup at this step.
        char flippedChar = realHmac.charAt(0) == 'a' ? 'b' : 'a';
        String tamperedHmac = flippedChar + realHmac.substring(1);

        mockMvc.perform(get("/api/v1/probe/ping")
                        .header("Authorization", "Bearer sk_live_" + keyId + "_" + tamperedHmac))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"))
                .andExpect(content().string(not(containsString("pong"))));
    }

    /**
     * Returns the generated tenantId so callers needing to seed
     * related rows under the same tenant (e.g. revoked_keys in the
     * nested Case 4) don't have to fabricate a second, disconnected
     * tenant — see Agent 4 ruling on seedRevocation() clarity.
     */
    private static UUID seedTenantProjectAndKey(String keyId, String environment, String... capabilities) {
        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        seedingJdbcTemplate.update(
                "INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)",
                tenantId, "Integration Test Tenant", "GENERAL");
        seedingJdbcTemplate.update(
                "INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, ?)",
                projectId, tenantId, "Integration Test Project", environment);

        seedingJdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            Array capabilitiesArray = connection.createArrayOf("text", capabilities);
            try (var ps = connection.prepareStatement(
                    "INSERT INTO api_keys (id, tenant_id, project_id, key_id, key_hash, "
                            + "environment, capabilities, key_version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)")) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, tenantId);
                ps.setObject(3, projectId);
                ps.setString(4, keyId);
                ps.setString(5, "unused-hash-hmac-is-recomputed-not-read-back");
                ps.setString(6, environment);
                ps.setArray(7, capabilitiesArray);
                ps.executeUpdate();
            }
            return null;
        });

        return tenantId;
    }

    /**
     * SecureRandom-based per Agent 4 ruling — java.util.Random is
     * not cryptographically secure and using it for keyId generation
     * in a shared test helper risks being copy-pasted into a context
     * where that matters, even though it's harmless for this
     * specific format-only use.
     */
    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String computeRealHmac(String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    HMAC_SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] bytes = mac.doFinal(keyId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Case 4 — revoked key, PostgreSQL fallback per ADR-006, with
     * Redis deliberately unreachable rather than mocked. Separate
     * ApplicationContext (distinct @DynamicPropertySource values)
     * so the shared outer Redis container used by cases 1-3 is
     * never stopped or mutated.
     */
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @Import(ProbeControllerTestConfig.class)
    class RevokedKeyPostgresFallbackTest {

        @DynamicPropertySource
        static void registerUnreachableRedis(DynamicPropertyRegistry registry) {
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
            registry.add("trustguard.auth-resolver.datasource.pool-name",
                    () -> "test-auth-resolver-pool-revoked-case");

            registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
            registry.add("spring.jpa.properties.hibernate.dialect",
                    () -> "org.hibernate.dialect.PostgreSQLDialect");

            // Deliberately unreachable. Fail-fast timeouts per Agent
            // 4 ruling — without these, Lettuce may wait on OS-level
            // TCP timeout (20+ seconds) before this case completes.
            registry.add("spring.data.redis.host", () -> "127.0.0.1");
            registry.add("spring.data.redis.port", () -> "1");
            registry.add("spring.data.redis.connect-timeout", () -> "500ms");
            registry.add("spring.data.redis.timeout", () -> "500ms");
        }

        @Autowired
        private MockMvc mockMvc;

        @Test
        void revoked_key_falls_back_to_postgres_when_redis_unreachable_returns_401_key_revoked()
                throws Exception {
            String keyId = randomHex(32);
            String hmac = computeRealHmac(keyId);
            UUID tenantId = seedTenantProjectAndKey(keyId, "PRODUCTION", "CHECK");
            seedRevocation(tenantId, keyId);

            mockMvc.perform(get("/api/v1/probe/ping")
                            .header("Authorization", "Bearer sk_live_" + keyId + "_" + hmac))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("KEY_REVOKED"))
                    .andExpect(content().string(not(containsString("pong"))));
        }

        /**
         * Uses the same tenantId as the corresponding api_keys row,
         * per Agent 4 ruling — the PostgreSQL fallback query
         * (checkPostgresRevocation) does not filter by tenant_id, so
         * this doesn't change query behavior, but seeding a matching
         * tenantId reflects realistic data and avoids misleading a
         * future reader into thinking the fallback path is
         * tenant-scoped. No phantom tenant row is created here.
         */
        private void seedRevocation(UUID tenantId, String keyId) {
            seedingJdbcTemplate.update(
                    "INSERT INTO revoked_keys (id, tenant_id, key_id) VALUES (?, ?, ?)",
                    UUID.randomUUID(), tenantId, keyId);
        }
    }
}