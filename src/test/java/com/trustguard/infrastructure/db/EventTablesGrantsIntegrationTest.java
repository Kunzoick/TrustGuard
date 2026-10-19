package com.trustguard.infrastructure.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.trustguard.infrastructure.db.EventTablesTestSupport.adminConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.appConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.currentUtcMonthStart;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertEvent;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertIdempotencyKey;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertOutbox;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.seedTenant;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.tenantConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criterion 10 / RULING 38 (least privilege, append-only). Checked twice: through the catalog
 * (has_table_privilege, has_column_privilege) and by running each operation as trustguard_app. Denied
 * operations must fail with SQLSTATE 42501 (insufficient privilege).
 */
class EventTablesGrantsIntegrationTest {

    private static final String SQLSTATE_PERMISSION_DENIED = "42501";
    private static final List<String> ALL_PRIVILEGES = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE");

    private static boolean tablePrivilege(String table, String privilege) throws SQLException {
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT has_table_privilege('trustguard_app', ?::text, ?::text)")) {
            statement.setString(1, "public." + table);
            statement.setString(2, privilege);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static boolean columnPrivilege(String table, String column, String privilege) throws SQLException {
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT has_column_privilege('trustguard_app', ?::text, ?::text, ?::text)")) {
            statement.setString(1, "public." + table);
            statement.setString(2, column);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static List<String> partitionNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection admin = adminConnection();
             Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                             + "WHERE i.inhparent = 'public.trust_events'::regclass")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static void assertDenied(Connection connection, String sql) {
        SQLException denied = assertThrows(SQLException.class, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        });
        assertEquals(SQLSTATE_PERMISSION_DENIED, denied.getSQLState());
    }

    private static OffsetDateTime receivedAt() throws SQLException {
        return currentUtcMonthStart().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static String currentPartition() throws SQLException {
        LocalDate current = currentUtcMonthStart();
        return String.format("trust_events_%04d_%02d", current.getYear(), current.getMonthValue());
    }

    // ---------- catalog level ----------

    @Test
    void trust_events_grants_match_ruling_38() throws SQLException {
        assertTrue(tablePrivilege("trust_events", "SELECT"));
        assertTrue(tablePrivilege("trust_events", "INSERT"));
        assertFalse(tablePrivilege("trust_events", "DELETE"));
        assertFalse(tablePrivilege("trust_events", "TRUNCATE"));
        assertTrue(columnPrivilege("trust_events", "risk_context_snapshot", "UPDATE"));
        assertFalse(columnPrivilege("trust_events", "actor_id", "UPDATE"));
        assertFalse(columnPrivilege("trust_events", "payload", "UPDATE"));
        assertFalse(columnPrivilege("trust_events", "received_at", "UPDATE"));
    }

    @Test
    void outbox_grants_match_ruling_38() throws SQLException {
        assertTrue(tablePrivilege("outbox_events", "SELECT"));
        assertTrue(tablePrivilege("outbox_events", "INSERT"));
        assertTrue(tablePrivilege("outbox_events", "UPDATE"));
        assertFalse(tablePrivilege("outbox_events", "DELETE"));
        assertFalse(tablePrivilege("outbox_events", "TRUNCATE"));
    }

    @Test
    void idempotency_keys_grants_match_ruling_38() throws SQLException {
        assertTrue(tablePrivilege("idempotency_keys", "SELECT"));
        assertTrue(tablePrivilege("idempotency_keys", "INSERT"));
        assertTrue(tablePrivilege("idempotency_keys", "DELETE"));
        assertFalse(tablePrivilege("idempotency_keys", "UPDATE"));
        assertFalse(tablePrivilege("idempotency_keys", "TRUNCATE"));
    }

    @Test
    void application_role_holds_no_privilege_on_any_partition() throws SQLException {
        List<String> partitions = partitionNames();

        assertFalse(partitions.isEmpty());
        for (String partition : partitions) {
            for (String privilege : ALL_PRIVILEGES) {
                assertFalse(tablePrivilege(partition, privilege), partition + " " + privilege);
            }
        }
    }

    // ---------- behavior as trustguard_app ----------

    @Test
    void trust_events_allows_select_insert_and_update_of_the_snapshot_only() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertEvent(connection, owner, eventId, received.minusHours(1), received,
                    "k-" + eventId));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE trust_events SET risk_context_snapshot = '{\"behavioralTrust\":0.9}'::jsonb "
                            + "WHERE event_id = ?")) {
                statement.setObject(1, eventId);
                assertEquals(1, statement.executeUpdate());
            }
        }
    }

    @Test
    void trust_events_denies_update_of_any_other_column() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "UPDATE trust_events SET actor_id = 'tampered'");
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "UPDATE trust_events SET payload = '{}'::jsonb");
        }
    }

    @Test
    void trust_events_denies_delete_and_truncate() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "DELETE FROM trust_events");
        }
        try (Connection connection = appConnection()) {
            assertDenied(connection, "TRUNCATE trust_events");
        }
    }

    @Test
    void partitions_cannot_be_read_or_written_directly() throws SQLException {
        String partition = currentPartition();
        EventTablesTestSupport.Fixture owner = seedTenant();

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "SELECT count(*) FROM " + partition);
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "DELETE FROM " + partition);
        }
        try (Connection connection = appConnection()) {
            assertDenied(connection, "TRUNCATE " + partition);
        }
    }

    @Test
    void current_partition_denies_direct_insert_and_update() throws SQLException {
        String partition = currentPartition();
        EventTablesTestSupport.Fixture owner = seedTenant();
        OffsetDateTime received = receivedAt();
        String insert = "INSERT INTO " + partition + " (event_id, tenant_id, project_id, actor_type, actor_id, "
                + "event_type, environment, occurred_at, received_at, sdk_version, ip_address, "
                + "idempotency_key, payload) VALUES ('" + UUID.randomUUID() + "', '" + owner.tenantId() + "', '"
                + owner.projectId() + "', 'USER', 'actor-1', 'LOGIN', 'PRODUCTION', '" + received
                + "'::timestamptz, '" + received + "'::timestamptz, '1.0.0', '203.0.113.7', 'direct', '{}'::jsonb)";

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, insert);
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "UPDATE " + partition + " SET risk_context_snapshot = '{}'::jsonb");
        }
    }

    @Test
    void outbox_allows_select_insert_and_update_but_denies_delete() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertOutbox(connection, owner, UUID.randomUUID(), receivedAt()));
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE outbox_events SET status = 'PUBLISHED', "
                        + "published_at = now(), retry_count = retry_count + 1"));
            }
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "DELETE FROM outbox_events");
        }
    }

    @Test
    void idempotency_keys_allows_select_insert_and_delete_but_denies_update() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertIdempotencyKey(connection, owner.tenantId(), "grant-key", UUID.randomUUID(),
                    expiresAt));
            try (Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("DELETE FROM idempotency_keys"));
            }
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertDenied(connection, "UPDATE idempotency_keys SET expires_at = now()");
        }
    }
}