package com.trustguard.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule 4.2 Vector 1 — must be @Enabled and must PASS.
 * Flyway runs as superuser (trustguard). Test queries run as
 * trustguard_app (non-superuser) so RLS actually applies.
 * Superusers bypass RLS regardless — connecting as superuser
 * would make this test meaningless.
 */
@SpringBootTest
@Testcontainers
@Transactional
public class TenantIsolationTest {

    private static final String TEST_APP_PASSWORD = "trustguard_app_dev";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("trustguard_test")
                    .withUsername("trustguard")
                    .withPassword("trustguard");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        // Flyway and HikariCP both connect as superuser at startup.
        // This ensures trustguard_app is created by V5 before any
        // test query runs. Tests switch to trustguard_app inline
        // via SET LOCAL ROLE to get real RLS enforcement.
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        //registry.add("spring.flyway.placeholders.trustguardAppPassword",
                //() -> TEST_APP_PASSWORD);
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.rabbitmq.host", () -> "localhost");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void switchToAppRole() {
        // Switch to trustguard_app role for RLS enforcement.
        // SET LOCAL ROLE is transaction-scoped — resets after
        // each test automatically. Superuser connection is
        // retained by HikariCP for the next test.
        jdbcTemplate.execute("SET LOCAL ROLE trustguard_app");
    }

    @Test
    void tenant_b_cannot_read_tenant_a_project_rows() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        // Insert as superuser context (tenantA's tenant_id set)
        jdbcTemplate.execute(
                "SET LOCAL ROLE trustguard");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)",
                tenantA, "Tenant A", "GENERAL");
        jdbcTemplate.update(
                "INSERT INTO tenants (id, name, category) VALUES (?, ?, ?)",
                tenantB, "Tenant B", "GENERAL");

        jdbcTemplate.execute(
                "SELECT set_config('app.tenant_id', '" + tenantA + "', true)");
        jdbcTemplate.update(
                "INSERT INTO projects (id, tenant_id, name, environment)"
                        + " VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), tenantA,
                "Tenant A Project", "PRODUCTION");

        // Now switch to trustguard_app and set Tenant B context
        // RLS should return zero rows
        jdbcTemplate.execute("SET LOCAL ROLE trustguard_app");
        jdbcTemplate.execute(
                "SELECT set_config('app.tenant_id', '" + tenantB + "', true)");
        //Debug
        String currentSetting= jdbcTemplate.queryForObject(
                "SELECT current_setting('app.tenant_id', true)", String.class);
        System.out.println("current app.tenant_id: " + currentSetting);
        System.out.println("Expected tenantB: "+ tenantB);

        List<UUID> visibleToTenantB = jdbcTemplate.queryForList(
                "SELECT id FROM projects", UUID.class);

        assertThat(visibleToTenantB).isEmpty();
    }
}