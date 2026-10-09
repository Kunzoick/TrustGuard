package com.trustguard.api.admin;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Criterion 22 / RULING 21. Applies the real migrations, then connects as the NON-superuser
 * trustguard_app role. The password is the one V5 sets for the role; if V5 changes, update APP_PASSWORD.
 */
@Testcontainers
class AdminGrantsIntegrationTest {

    private static final String APP_USER = "trustguard_app";
    private static final String APP_PASSWORD = "trustguard_app_dev";
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("trustguard_grants")
            .withUsername("trustguard")
            .withPassword("trustguard");

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of(
                        "trustguardAppPassword", APP_PASSWORD,
                        "trustguardAuthResolverPassword", "test-only-not-for-production"))
                .load()
                .migrate();
        try (Connection superuser = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement statement = superuser.createStatement()) {
            statement.executeUpdate("INSERT INTO admin_users (id, username, password_hash) VALUES ('"
                    + ADMIN_ID + "', 'grant-test-admin', 'hash')");
        }
    }

    private static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    @Test
    void app_role_can_select_and_update_admin_users() throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            assertTrue(statement.executeQuery("SELECT id FROM admin_users").next());
            assertEquals(1, statement.executeUpdate(
                    "UPDATE admin_users SET failed_attempt_count = 1 WHERE id = '" + ADMIN_ID + "'"));
        }
    }

    @Test
    void app_role_can_insert_security_events() throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "INSERT INTO security_events (event_type) VALUES ('GRANT_TEST')"));
        }
    }

    @Test
    void app_role_cannot_delete_from_security_events() throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM security_events"));
            assertTrue(denied.getMessage().toLowerCase().contains("permission denied"));
        }
    }

    @Test
    void app_role_cannot_delete_from_admin_users() throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            SQLException denied = assertThrows(SQLException.class,
                    () -> statement.executeUpdate("DELETE FROM admin_users"));
            assertTrue(denied.getMessage().toLowerCase().contains("permission denied"));
        }
    }

    @Test
    void app_role_cannot_read_security_events_or_insert_admin_users() throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM security_events"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO admin_users (id, username, password_hash) VALUES ('"
                            + UUID.randomUUID() + "', 'nope', 'hash')"));
        }
    }
}