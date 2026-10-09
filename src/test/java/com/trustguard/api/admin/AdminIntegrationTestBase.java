package com.trustguard.api.admin;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared setup for admin integration tests. Singleton containers (started once per JVM) and identical
 * properties let Spring cache one context across all subclasses. Admin users are seeded with JdbcTemplate
 * and a bcrypt hash computed once (cost 12 is ~250 ms). Open-in-view is forced on so the concurrency test
 * exercises the worst case regardless of the project default (RULING 23).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminTestSupportConfig.class)
public abstract class AdminIntegrationTestBase {

    protected static final String ADMIN_JWT_SECRET = "dGVzdC1vbmx5LWFkbWluLWp3dC1zZWNyZXQtMzItYnl0ZXM=";
    protected static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
    protected static final String PASSWORD = "correct-horse-battery-staple";
    private static final String APP_PASSWORD = "test-only-not-for-production";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("trustguard_test")
            .withUsername("trustguard")
            .withPassword("trustguard");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    /** Superuser template, used only for seeding and assertions. */
    protected static final JdbcTemplate JDBC;

    static {
        POSTGRES.start();
        REDIS.start();
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JDBC = new JdbcTemplate(dataSource);
    }

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
        registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-admin-auth-resolver-pool");

        registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
        registry.add("trustguard.admin.jwt-secret", () -> ADMIN_JWT_SECRET);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.open-in-view", () -> "true");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    /** Seeds an admin with a unique username and the shared password; returns the username. */
    protected static String seedAdmin() {
        String username = "admin-" + UUID.randomUUID();
        JDBC.update("INSERT INTO admin_users (id, username, password_hash) VALUES (?, ?, ?)",
                UUID.randomUUID(), username, PASSWORD_HASH);
        return username;
    }

    protected static int failedCount(String username) {
        return JDBC.queryForObject("SELECT failed_attempt_count FROM admin_users WHERE username = ?",
                Integer.class, username);
    }

    protected static boolean isLocked(String username) {
        return JDBC.queryForObject("SELECT locked_at IS NOT NULL FROM admin_users WHERE username = ?",
                Boolean.class, username);
    }

    protected ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
    }

    protected String loginForToken(String username) throws Exception {
        String body = login(username, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}