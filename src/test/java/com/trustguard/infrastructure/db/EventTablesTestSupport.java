package com.trustguard.infrastructure.db;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared setup for the B-008 event-table tests. One singleton PostgreSQL container per JVM, migrated once
 * with the real Flyway migrations. Every assertion on trust_events, outbox_events and idempotency_keys must
 * use a trustguard_app connection (the superuser bypasses RLS, so it would prove nothing); the superuser
 * is used only to seed tenants and projects and to read the catalog.
 */
public class EventTablesTestSupport {
    static final String APP_USER= "trustguard_app";
    static final String APP_PASSWORD= "trustguard_app_dev";
    private static final String AUTH_RESOLVER_PASSWORD= "test-only-not-for-production";
    static final PostgreSQLContainer<?> POSTGRES= new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("trustguard_events").withUsername("trustguard").withPassword("trustguard");
    static {
        POSTGRES.start();
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .placeholders(Map.of("trustguardAppPassword", APP_PASSWORD, "trustguardAuthResolverPassword",
                        AUTH_RESOLVER_PASSWORD)).load().migrate();
    }
    private EventTablesTestSupport() {
    }
    //A tenant and one of its projects, seeded as superuser.
    record Fixture(UUID tenantId, UUID projectId){}
    static Connection adminConnection() throws SQLException{
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    //A plain trustguard_app connection with no tenant context. Autocommit is on
    static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }
    /*
    A trustguard_app connection inside a transaction with app.tenant_id set transaction-locally.
    The caller must commit to keep change; closing without a commits rolls back.
     */
    static Connection tenantConnection(UUID tenantId) throws SQLException{
        Connection connection= appConnection();
        connection.setAutoCommit(false);
        try(PreparedStatement statement= connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")){
            statement.setString(1, tenantId.toString());
            statement.execute();
        }
        return connection;
    }
    //seeds a tenant with one project as superuser(tenants and projects are fixtures not under tests)
    static Fixture seedTenant() throws SQLException{
        UUID tenantId= UUID.randomUUID();
        UUID projectId= UUID.randomUUID();
        try(Connection admin= adminConnection()){
            try(PreparedStatement statement= admin.prepareStatement("INSERT INTO tenants (id, name, category) VALUES (?, ?, 'GENERAL')")){
                statement.setObject(1, tenantId);
                statement.setString(2, "tenant-"+ tenantId);
                statement.executeUpdate();
            }
            try(PreparedStatement statement= admin.prepareStatement
                    ("INSERT INTO projects (id, tenant_id, name, environment) VALUES (?, ?, ?, 'PRODUCTION')")){
                statement.setObject(1, projectId);
                statement.setObject(2, tenantId);
                statement.setString(3, "project-" + projectId);
                statement.executeUpdate();
            }
        }
        return new Fixture(tenantId, projectId);
    }
    //inserts one trust events row on the given connection. Returns the affected row count
    static int insertEvent(Connection connection, Fixture owner, UUID eventId, OffsetDateTime occurredAt,
                           OffsetDateTime receivedAt, String idempotencyKey) throws SQLException{
        try(PreparedStatement statement= connection.prepareStatement
                ("INSERT INTO trust_events (event_id, tenant_id, project_id, actor_type, actor_id, event_type, "
                + "environment, occurred_at, received_at, sdk_version, ip_address, idempotency_key, payload) "
                + "VALUES (?, ?, ?, 'USER', 'actor-1', 'LOGIN', 'PRODUCTION', ?, ?, '1.0.0', '203.0.113.7', "
                + "?, '{}'::jsonb)")){
            statement.setObject(1, eventId);
            statement.setObject(2, owner.tenantId());
            statement.setObject(3, owner.projectId());
            statement.setObject(4, occurredAt);
            statement.setObject(5, receivedAt);
            statement.setString(6, idempotencyKey);
            return statement.executeUpdate();
        }
    }
    //inserts one outbox events row on the given connection
    static int insertOutbox(Connection connection, Fixture owner, UUID eventId, OffsetDateTime eventReceivedAt)throws SQLException{
        try(PreparedStatement statement= connection.prepareStatement
                ("INSERT INTO outbox_events (tenant_id, event_id, event_received_at, payload) "
                + "VALUES (?, ?, ?, '{}'::jsonb)")){
            statement.setObject(1, owner.tenantId());
            statement.setObject(2, eventId);
            statement.setObject(3, eventReceivedAt);
            return statement.executeUpdate();
        }
    }
    /** Inserts one idempotency_keys row on the given connection. Returns the affected row count. */
    static int insertIdempotencyKey(Connection connection, UUID tenantId, String key, UUID eventId,
                                    OffsetDateTime expiresAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO idempotency_keys (tenant_id, idempotency_key, event_id, expires_at) "
                        + "VALUES (?, ?, ?, ?)")) {
            statement.setObject(1, tenantId);
            statement.setString(2, key);
            statement.setObject(3, eventId);
            statement.setObject(4, expiresAt);
            return statement.executeUpdate();
        }
    }

    /** Counts rows visible on the given connection. The table name is a test constant, never input. */
    static int count(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM " + table);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    /** The first day of the current UTC month according to the database clock. */
    static LocalDate currentUtcMonthStart() throws SQLException {
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT date_trunc('month', now() AT TIME ZONE 'UTC')::date");
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getObject(1, LocalDate.class);
        }
    }
}
