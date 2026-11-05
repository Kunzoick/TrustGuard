package com.trustguard.signals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import com.trustguard.shared.domain.ActorId;
import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.ActorType;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.shared.enums.EventType;
import com.trustguard.signals.domain.EventEnvelope;
import com.trustguard.tenant.context.TenantContext;
import com.trustguard.tenant.context.TenantContextHolder;

/**
 * Shared setup for the B-010c persistence tests. One singleton PostgreSQL container, started once per JVM. The
 * primary data source connects as trustguard_app (the non-superuser role), so row level security is really
 * enforced; Flyway keeps its own url and the container superuser. Redis and RabbitMQ point at a closed port,
 * so every context is one where the broker is unreachable (Ruling 100). The EVENT_HMAC_KEY comes from the test
 * application.properties (Ruling 88).
 *
 * <p>Self-contained on purpose: signals tests may not import the api or infrastructure modules (Rule 2.2).
 */
@SpringBootTest
public abstract class SignalsIntegrationTestBase {

    protected static final String APP_ROLE = "trustguard_app";
    private static final String APP_ROLE_PASSWORD = "trustguard_app_dev";
    private static final String PLACEHOLDER_PASSWORD = "test-only-not-for-production";
    private static final String HMAC_SIGNING_KEY = "integration-test-hmac-signing-key";
    private static final String ADMIN_JWT_SECRET = "dGVzdC1vbmx5LWFkbWluLWp3dC1zZWNyZXQtMzItYnl0ZXM=";
    private static final String UNREACHABLE_HOST = "127.0.0.1";
    private static final String UNREACHABLE_PORT = "1";
    private static final String FAST_TIMEOUT = "500ms";
    private static final long CLAIM_AGE_SECONDS = 60;

    private static final Path OBSERVATIONS = Path.of("target", "b010c-observed.txt");

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("trustguard_test")
            .withUsername("trustguard")
            .withPassword("trustguard");

    /** Superuser template: fixtures and assertions only. It bypasses row level security. */
    protected static final JdbcTemplate SUPERUSER;

    static {
        POSTGRES.start();
        DriverManagerDataSource superuserSource = new DriverManagerDataSource();
        superuserSource.setUrl(POSTGRES.getJdbcUrl());
        superuserSource.setUsername(POSTGRES.getUsername());
        superuserSource.setPassword(POSTGRES.getPassword());
        SUPERUSER = new JdbcTemplate(superuserSource);
    }

    /** A seeded tenant and one of its projects. */
    protected record Seeded(TenantId tenantId, ProjectId projectId) {
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.trustguardAppPassword", () -> PLACEHOLDER_PASSWORD);
        registry.add("spring.flyway.placeholders.trustguardAuthResolverPassword", () -> PLACEHOLDER_PASSWORD);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE_PASSWORD);

        registry.add("trustguard.auth-resolver.datasource.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("trustguard.auth-resolver.datasource.username", POSTGRES::getUsername);
        registry.add("trustguard.auth-resolver.datasource.password", POSTGRES::getPassword);
        registry.add("trustguard.auth-resolver.datasource.minimum-idle", () -> "1");
        registry.add("trustguard.auth-resolver.datasource.maximum-pool-size", () -> "3");
        registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-signals-auth-resolver-pool");

        registry.add("trustguard.security.hmac-signing-key", () -> HMAC_SIGNING_KEY);
        registry.add("trustguard.admin.jwt-secret", () -> ADMIN_JWT_SECRET);
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("spring.data.redis.host", () -> UNREACHABLE_HOST);
        registry.add("spring.data.redis.port", () -> UNREACHABLE_PORT);
        registry.add("spring.data.redis.connect-timeout", () -> FAST_TIMEOUT);
        registry.add("spring.data.redis.timeout", () -> FAST_TIMEOUT);
        registry.add("spring.rabbitmq.host", () -> UNREACHABLE_HOST);
        registry.add("spring.rabbitmq.port", () -> UNREACHABLE_PORT);
    }

    /** The primary data source, which resolves to the trustguard_app connection. */
    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected PlatformTransactionManager transactionManager;

    // ---------- fixtures ----------

    /**
     * Seeds a new tenant with one project, as the superuser.
     *
     * @return the new tenant and project ids
     */
    protected static Seeded seed() {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        SUPERUSER.update("INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)",
                tenant, "Signals Test Tenant", "GENERAL");
        SUPERUSER.update("INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, ?)",
                project, tenant, "Signals Test Project", "PRODUCTION");
        return new Seeded(new TenantId(tenant.toString()), new ProjectId(project.toString()));
    }

    /**
     * The database clock, at microsecond precision.
     *
     * @return now according to PostgreSQL
     */
    protected static Instant dbNow() {
        return SUPERUSER.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    /**
     * A valid envelope for a seeded tenant and project, received now by the database clock.
     *
     * @param seeded the tenant and project the event belongs to
     * @return a builder holding a complete valid envelope; tests change single fields
     */
    protected static EventEnvelope.EventEnvelopeBuilder envelopeFor(Seeded seeded) {
        Instant now = dbNow();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", "v");
        payload.put("n", 1);
        payload.put("l", Arrays.asList(1, null));
        return EventEnvelope.builder()
                .eventId(new EventId(UUID.randomUUID().toString()))
                .tenantId(seeded.tenantId())
                .projectId(seeded.projectId())
                .actorType(ActorType.USER)
                .actorId(new ActorId("user-42"))
                .sessionId("session-1")
                .eventType(EventType.LOGIN)
                .environment(Environment.PRODUCTION)
                .occurredAt(now.minusSeconds(CLAIM_AGE_SECONDS))
                .receivedAt(now)
                .sdkVersion("1.0.0")
                .ipAddress("203.0.113.7")
                .userAgent("Mozilla/5.0")
                .deviceFingerprint("fp-1")
                .idempotencyKey("idem-" + UUID.randomUUID())
                .payload(payload);
    }

    // ---------- tenant transactions ----------

    /**
     * Runs work in one transaction with app.tenant_id set to the tenant, like the RLS aspect does for a service.
     *
     * @param tenant the tenant for row level security
     * @param work   what to run inside the transaction
     * @param <T>    the result type
     * @return the work's result
     */
    protected <T> T callInTenant(TenantId tenant, Supplier<T> work) {
        JdbcTemplate app = new JdbcTemplate(dataSource);
        return new TransactionTemplate(transactionManager).execute(status -> {
            app.queryForObject("SELECT set_config('app.tenant_id', ?, true)", String.class, tenant.value());
            return work.get();
        });
    }

    /**
     * Runs work in a tenant transaction and returns nothing.
     *
     * @param tenant the tenant for row level security
     * @param work   what to run inside the transaction
     */
    protected void runInTenant(TenantId tenant, Runnable work) {
        callInTenant(tenant, () -> {
            work.run();
            return null;
        });
    }

    // ---------- assertions helpers ----------

    /**
     * Counts the event rows with an id, as the superuser.
     *
     * @param eventId the event id
     * @return the number of trust_events rows
     */
    protected static int countEvents(String eventId) {
        return SUPERUSER.queryForObject("SELECT count(*) FROM trust_events WHERE event_id = ?::uuid",
                Integer.class, eventId);
    }

    /**
     * Counts the outbox rows of an event, as the superuser.
     *
     * @param eventId the event id
     * @return the number of outbox_events rows
     */
    protected static int countOutbox(String eventId) {
        return SUPERUSER.queryForObject("SELECT count(*) FROM outbox_events WHERE event_id = ?::uuid",
                Integer.class, eventId);
    }

    /**
     * The first SQLSTATE in the cause chain.
     *
     * @param throwable the failure
     * @return the SQLSTATE, or "none"
     */
    protected static String sqlState(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return "none";
    }

    /**
     * Appends one observation to target/b010c-observed.txt.
     *
     * @param line what was observed
     */
    protected static void observe(String line) {
        try {
            Files.createDirectories(OBSERVATIONS.getParent());
            Files.writeString(OBSERVATIONS, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
    /**
     * Sets a valid tenantContext for the duration of the test, so the services tiggered during the test can
     * read it
     * @param seeded the tenant and the project to put in the context
     */
    protected void authenticateAs(Seeded seeded){
        TenantContextHolder.set(new TenantContext(seeded.tenantId(), seeded.projectId(),
                "test-key-id", Environment.PRODUCTION, Set.of(Capability.TRACK, Capability.CHECK),
                1));
    }
    @AfterEach
    void clearTenantContext(){
        TenantContextHolder.clear();
    }
}