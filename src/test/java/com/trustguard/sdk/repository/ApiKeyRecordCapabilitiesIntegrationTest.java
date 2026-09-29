package com.trustguard.sdk.repository;

import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CF-015 / Adjustment 3 — proves whether Set<Capability> round-trips
 * correctly through api_keys.capabilities (Postgres TEXT[]) using
 * whatever annotation configuration ApiKeyRecord currently has.
 *
 * Compilation success is NOT sufficient evidence per Adjustment 3 —
 * this test must actually execute against real PostgreSQL via
 * Testcontainers and assert on the round-tripped value.
 *
 * This test connects as trustguard (superuser bootstrap credentials
 * from Testcontainers), not trustguard_app or trustguard_authresolver
 * — RLS is irrelevant to what this test is proving, which is purely
 * the JDBC/Hibernate array-binding behavior.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class ApiKeyRecordCapabilitiesIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("trustguard_test")
                    .withUsername("trustguard")
                    .withPassword("trustguard");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.trustguardAppPassword",
                () -> "test-only-not-for-production");
        registry.add("spring.flyway.placeholders.trustguardAuthResolverPassword",
                () -> "test-only-not-for-production");

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        //Auth resolver datasource- same container, same superuser, credentials for test purposes.
        registry.add("trustguard.auth-resolver.datasource.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("trustguard.auth-resolver.datasource.username", POSTGRES::getUsername);
        registry.add("trustguard.auth-resolver.datasource.password", POSTGRES::getPassword);
        registry.add("trustguard.auth-resolver.datasource.minimum-idle", () -> "1");
        registry.add("trustguard.auth-resolver.datasource.maximum-pool-size", () -> "2");
        registry.add("trustguard.auth-resolver.datasource.pool-name", () -> "test-capabilities-pool");

        //HMAC signing key required by KeyHashVerificationService bean
        registry.add("trustguard.security.hmac-signing-key", () -> "test-only-insecure-key");

        //Redis- point at a non-existent port so it fails fast as test doesn't exercise Redis at all
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> "1");
        registry.add("spring.data.redis.connect-timeout", () -> "500ms");
        registry.add("spring.data.redis.timeout", () -> "500ms");
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Test
    @Transactional
    void capabilities_set_round_trips_correctly_through_postgres_text_array() {
        // A fresh EntityManager forces an actual SELECT rather than
        // returning the same managed instance from the persistence
        // context — otherwise this test could pass while the actual
        // JDBC binding is silently broken.
        EntityManager writeEm = entityManagerFactory.createEntityManager();
        writeEm.getTransaction().begin();

        UUID tenantId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        writeEm.createNativeQuery(
                        "INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)")
                .setParameter(1, tenantId).setParameter(2, "Test Tenant")
                .setParameter(3, "GENERAL").executeUpdate();
        writeEm.createNativeQuery(
                        "INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, ?)")
                .setParameter(1, projectId).setParameter(2, tenantId)
                .setParameter(3, "Test Project").setParameter(4, "PRODUCTION").executeUpdate();

        ApiKeyRecord record = new ApiKeyRecord();
        // Using reflection-free direct field access is not available
        // (fields are private, constructor is protected/no-arg for
        // JPA) — inserting via native SQL instead to isolate what
        // we're actually testing: reading capabilities back out,
        // not the write path, which uses the same converter/type
        // machinery and would confound the result if it also failed.
        writeEm.createNativeQuery(
                        "INSERT INTO api_keys (id, tenant_id, project_id, key_id, key_hash, "
                                + "environment, capabilities, key_version) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, 1)")
                .setParameter(1, UUID.randomUUID())
                .setParameter(2, tenantId)
                .setParameter(3, projectId)
                .setParameter(4, "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
                .setParameter(5, "irrelevant-hash-not-under-test")
                .setParameter(6, "PRODUCTION")
                .setParameter(7, new String[] {"CHECK", "TRACK"})
                .executeUpdate();

        writeEm.getTransaction().commit();
        writeEm.close();

        EntityManager readEm = entityManagerFactory.createEntityManager();
        ApiKeyRecord fetched = readEm.createQuery(
                        "SELECT k FROM ApiKeyRecord k WHERE k.keyId = :keyId", ApiKeyRecord.class)
                .setParameter("keyId", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4")
                .getSingleResult();

        assertThat(fetched.getCapabilities())
                .containsExactlyInAnyOrder(Capability.CHECK, Capability.TRACK);
        assertThat(fetched.getEnvironment()).isEqualTo(Environment.PRODUCTION);

        readEm.close();
    }
}