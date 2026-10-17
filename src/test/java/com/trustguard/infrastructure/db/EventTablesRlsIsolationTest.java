package com.trustguard.infrastructure.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.trustguard.infrastructure.db.EventTablesTestSupport.appConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.count;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.currentUtcMonthStart;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertEvent;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertIdempotencyKey;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertOutbox;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.seedTenant;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.tenantConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Acceptance criteria 8 and 9 / RULINGS 39 and 40. Every statement on trust_events, outbox_events and
 * idempotency_keys runs as trustguard_app (the superuser would bypass RLS). Tenant A's rows must be
 * invisible and untouchable to tenant B, and tenant B must not be able to insert rows owned by tenant A.
 */
class EventTablesRlsIsolationTest {

    private static final String SQLSTATE_RLS_VIOLATION = "42501";

    private static OffsetDateTime receivedAt() throws SQLException {
        return currentUtcMonthStart().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime inOneDay() {
        return OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    // ---------- trust_events ----------

    @Test
    void trust_events_of_another_tenant_are_invisible() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        UUID eventId = UUID.randomUUID();
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertEvent(connection, a, eventId, received.minusHours(1), received, "k-" + eventId));
            connection.commit();
        }

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, count(connection, "trust_events"));
        }
        try (Connection connection = tenantConnection(b.tenantId())) {
            assertEquals(0, count(connection, "trust_events"));
        }
    }

    @Test
    void trust_events_cannot_be_inserted_for_another_tenant() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(b.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertEvent(connection, a,
                    UUID.randomUUID(), received.minusHours(1), received, "cross-tenant"));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    // ---------- outbox_events ----------

    @Test
    void outbox_rows_of_another_tenant_are_invisible_and_untouchable() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertOutbox(connection, a, UUID.randomUUID(), receivedAt()));
            connection.commit();
        }

        try (Connection connection = tenantConnection(b.tenantId())) {
            assertEquals(0, count(connection, "outbox_events"));
            assertEquals(0, update(connection, "UPDATE outbox_events SET status = 'PUBLISHED'"));
        }
        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, count(connection, "outbox_events"));
            assertEquals("PENDING", singleString(connection, "SELECT status FROM outbox_events"));
        }
    }

    @Test
    void outbox_rows_cannot_be_inserted_for_another_tenant() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(b.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertOutbox(connection, a, UUID.randomUUID(), received));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    // ---------- idempotency_keys ----------

    @Test
    void idempotency_keys_of_another_tenant_are_invisible_and_untouchable() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertIdempotencyKey(connection, a.tenantId(), "shared-key", UUID.randomUUID(),
                    inOneDay()));
            connection.commit();
        }

        try (Connection connection = tenantConnection(b.tenantId())) {
            assertEquals(0, count(connection, "idempotency_keys"));
            assertEquals(0, update(connection, "DELETE FROM idempotency_keys"));
        }
        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, count(connection, "idempotency_keys"));
        }
    }

    @Test
    void idempotency_keys_cannot_be_inserted_for_another_tenant() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        OffsetDateTime expiresAt = inOneDay();

        try (Connection connection = tenantConnection(b.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertIdempotencyKey(connection,
                    a.tenantId(), "cross-tenant", UUID.randomUUID(), expiresAt));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void two_tenants_may_use_the_same_idempotency_key() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        OffsetDateTime expiresAt = inOneDay();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertIdempotencyKey(connection, a.tenantId(), "same", UUID.randomUUID(), expiresAt));
            connection.commit();
        }
        try (Connection connection = tenantConnection(b.tenantId())) {
            assertEquals(1, insertIdempotencyKey(connection, b.tenantId(), "same", UUID.randomUUID(), expiresAt));
            connection.commit();
        }
    }

    // ---------- no tenant context ----------

    @Test
    void a_connection_without_tenant_context_sees_nothing_and_cannot_insert() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        OffsetDateTime received = receivedAt();
        try (Connection connection = tenantConnection(a.tenantId())) {
            insertEvent(connection, a, UUID.randomUUID(), received.minusHours(1), received, "visible-to-a");
            insertOutbox(connection, a, UUID.randomUUID(), received);
            insertIdempotencyKey(connection, a.tenantId(), "visible-to-a", UUID.randomUUID(), inOneDay());
            connection.commit();
        }

        try (Connection connection = appConnection()) {
            assertEquals(0, count(connection, "trust_events"));
            assertEquals(0, count(connection, "outbox_events"));
            assertEquals(0, count(connection, "idempotency_keys"));
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertIdempotencyKey(connection, a.tenantId(), "no-context", UUID.randomUUID(),
                            inOneDay()));
            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void a_reused_connection_after_a_tenant_transaction_matches_no_rows_and_does_not_error() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(a.tenantId())) {
            insertEvent(connection, a, UUID.randomUUID(), received.minusHours(1), received, "reuse");
            insertOutbox(connection, a, UUID.randomUUID(), received);
            insertIdempotencyKey(connection, a.tenantId(), "reuse", UUID.randomUUID(), inOneDay());
            connection.commit();

            // Same physical connection, now outside any tenant transaction. What the setting reads as here
            // (empty string or unset) is server behavior, so the natural case is checked first and the
            // empty-string case is then pinned explicitly below.
            connection.setAutoCommit(true);
            assertNoRowsVisible(connection);

            // A policy without NULLIF would raise an invalid uuid error on this value (the V2 trap).
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '', false)");
            }
            assertNoRowsVisible(connection);
        }
    }
    private static void assertNoRowsVisible(Connection connection) throws SQLException {
        assertEquals(0, count(connection, "trust_events"));
        assertEquals(0, count(connection, "outbox_events"));
        assertEquals(0, count(connection, "idempotency_keys"));
    }
}