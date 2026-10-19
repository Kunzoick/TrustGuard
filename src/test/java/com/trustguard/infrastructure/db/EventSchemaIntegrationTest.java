package com.trustguard.infrastructure.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
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
 * Acceptance criteria 1-7, 11, 12 and Ruling 43: the structure of the three event tables, the partition
 * function and partition routing. Catalog reads use the superuser; behavior is exercised as trustguard_app.
 */
class EventSchemaIntegrationTest {

    private static final String SQLSTATE_INVALID_PARAMETER = "22023";
    private static final String SQLSTATE_CHECK_VIOLATION = "23514";
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    private static final int WINDOW_MONTHS_AHEAD = 12;
    private static final int TIME_ZONE_TEST_MONTHS_AHEAD = 3;

    private static String partitionName(LocalDate monthStart) {
        return String.format("trust_events_%04d_%02d", monthStart.getYear(), monthStart.getMonthValue());
    }

    private static String createPartition(Connection connection, LocalDate month) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT create_trust_events_partition(?::date)")) {
            statement.setObject(1, month);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static void assertRejected(LocalDate month) throws SQLException {
        try (Connection connection = appConnection()) {
            SQLException rejected = assertThrows(SQLException.class, () -> createPartition(connection, month));
            assertEquals(SQLSTATE_INVALID_PARAMETER, rejected.getSQLState());
        }
    }

    private static List<String> partitionNames() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection admin = adminConnection();
             Statement statement = admin.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid "
                             + "WHERE i.inhparent = 'public.trust_events'::regclass ORDER BY c.relname")) {
            while (result.next()) {
                names.add(result.getString(1));
            }
        }
        return names;
    }

    private static String stringQuery(String sql, String... parameters) throws SQLException {
        try (Connection admin = adminConnection(); PreparedStatement statement = admin.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static List<String> columnNames(String table) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                             + "AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    names.add(result.getString(1));
                }
            }
        }
        return names;
    }

    private static List<String> indexDefinitions(String table) throws SQLException {
        List<String> definitions = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    definitions.add(result.getString(1));
                }
            }
        }
        return definitions;
    }

    private static String boundsInUtc(String partition) throws SQLException {
        try (Connection admin = adminConnection()) {
            try (Statement statement = admin.createStatement()) {
                statement.execute("SET TIME ZONE 'UTC'");
                statement.execute("SET DateStyle = 'ISO, MDY'");
            }
            try (PreparedStatement statement = admin.prepareStatement(
                    "SELECT pg_get_expr(relpartbound, oid) FROM pg_class "
                            + "WHERE relname = ? AND relnamespace = 'public'::regnamespace")) {
                statement.setString(1, partition);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        }
    }

    private static String expectedBounds(LocalDate monthStart) {
        return "FOR VALUES FROM ('" + monthStart + " 00:00:00+00') TO ('" + monthStart.plusMonths(1)
                + " 00:00:00+00')";
    }

    private static OffsetDateTime startOfDayUtc(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    // ---------- AC 1, 5: partitioning and primary key ----------

    @Test
    void trust_events_is_range_partitioned_by_received_at() throws SQLException {
        String strategy = stringQuery("SELECT partstrat FROM pg_partitioned_table "
                + "WHERE partrelid = 'public.trust_events'::regclass");
        String key = stringQuery("SELECT pg_get_partkeydef('public.trust_events'::regclass)");

        assertEquals("r", strategy);
        assertEquals("RANGE (received_at)", key);
    }

    @Test
    void primary_key_includes_the_partition_key() throws SQLException {
        String definition = stringQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'public.trust_events'::regclass AND contype = 'p'");

        assertEquals("PRIMARY KEY (event_id, received_at)", definition);
    }

    // ---------- AC 3: partitions and UTC bounds ----------

    @Test
    void current_and_next_month_partitions_exist_with_utc_bounds() throws SQLException {
        LocalDate current = currentUtcMonthStart();
        List<String> names = partitionNames();

        assertTrue(names.contains(partitionName(current)));
        assertTrue(names.contains(partitionName(current.plusMonths(1))));
        assertEquals(expectedBounds(current), boundsInUtc(partitionName(current)));
        assertEquals(expectedBounds(current.plusMonths(1)), boundsInUtc(partitionName(current.plusMonths(1))));
    }

    @Test
    void partition_bounds_do_not_depend_on_the_session_time_zone() throws SQLException {
        LocalDate month = currentUtcMonthStart().plusMonths(TIME_ZONE_TEST_MONTHS_AHEAD);

        String name;
        try (Connection connection = appConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'Pacific/Auckland'");
            }
            name = createPartition(connection, month);
        }

        assertEquals(partitionName(month), name);
        assertEquals(expectedBounds(month), boundsInUtc(name));
    }

    // ---------- AC 4: local indexes ----------

    @Test
    void each_partition_has_local_indexes_for_the_hot_columns() throws SQLException {
        LocalDate current = currentUtcMonthStart();
        for (String partition : List.of(partitionName(current), partitionName(current.plusMonths(1)))) {
            String definitions = String.join("\n", indexDefinitions(partition));

            assertTrue(definitions.contains("(tenant_id, actor_id, occurred_at)"), partition);
            assertTrue(definitions.contains("(tenant_id, event_type, occurred_at)"), partition);
            assertTrue(definitions.contains("(tenant_id, received_at)"), partition);
            assertTrue(definitions.contains("(event_id, received_at)"), partition);
        }
    }

    // ---------- AC 2, 11 and Ruling 43: the partition function ----------

    @Test
    void function_is_idempotent_and_returns_the_partition_name() throws SQLException {
        LocalDate month = currentUtcMonthStart().plusMonths(2);

        try (Connection connection = appConnection()) {
            String first = createPartition(connection, month);
            String second = createPartition(connection, month);

            assertEquals(partitionName(month), first);
            assertEquals(first, second);
        }
    }

    @Test
    void function_rejects_a_date_that_is_not_the_first_of_a_month() throws SQLException {
        assertRejected(currentUtcMonthStart().plusDays(1));
        assertRejected(currentUtcMonthStart().plusMonths(1).plusDays(14));
    }

    @Test
    void function_rejects_null() throws SQLException {
        try (Connection connection = appConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT create_trust_events_partition(?)")) {
            statement.setNull(1, Types.DATE);

            SQLException rejected = assertThrows(SQLException.class, statement::executeQuery);

            assertEquals(SQLSTATE_INVALID_PARAMETER, rejected.getSQLState());
        }
    }

    @Test
    void function_rejects_a_month_earlier_than_the_previous_month() throws SQLException {
        assertRejected(currentUtcMonthStart().minusMonths(2));
    }

    @Test
    void function_accepts_the_previous_month_inclusive() throws SQLException {
        LocalDate previous = currentUtcMonthStart().minusMonths(1);

        try (Connection connection = appConnection()) {
            assertEquals(partitionName(previous), createPartition(connection, previous));
        }
    }

    @Test
    void function_rejects_a_month_more_than_twelve_months_ahead() throws SQLException {
        assertRejected(currentUtcMonthStart().plusMonths(WINDOW_MONTHS_AHEAD + 1));
    }

    @Test
    void function_accepts_current_month_plus_twelve_inclusive() throws SQLException {
        LocalDate latest = currentUtcMonthStart().plusMonths(WINDOW_MONTHS_AHEAD);

        try (Connection connection = appConnection()) {
            assertEquals(partitionName(latest), createPartition(connection, latest));
        }
    }

    @Test
    void application_role_can_execute_the_function() throws SQLException {
        String executable = stringQuery("SELECT has_function_privilege('trustguard_app', "
                + "'create_trust_events_partition(date)', 'EXECUTE')::text");

        assertEquals("true", executable);
    }

    @Test
    void function_has_a_lock_timeout_attached() throws SQLException {
        String settings = stringQuery("SELECT proconfig::text FROM pg_proc WHERE proname = "
                + "'create_trust_events_partition' AND pronamespace = 'public'::regnamespace");

        assertTrue(settings.contains("lock_timeout=5s"), settings);
        assertTrue(settings.contains("search_path="), settings);
    }

    // ---------- AC 12: the partition key is received_at ----------

    @Test
    void event_received_this_month_but_occurred_last_month_lands_in_the_current_month_partition()
            throws SQLException {
        LocalDate current = currentUtcMonthStart();
        OffsetDateTime receivedAt = startOfDayUtc(current.plusDays(1));
        OffsetDateTime occurredAt = startOfDayUtc(current.minusMonths(1).plusDays(14));
        UUID eventId = UUID.randomUUID();
        EventTablesTestSupport.Fixture owner = seedTenant();

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertEvent(connection, owner, eventId, occurredAt, receivedAt, "key-" + eventId));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tableoid::regclass::text FROM trust_events WHERE event_id = ?")) {
                statement.setObject(1, eventId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    assertEquals(partitionName(current), result.getString(1));
                }
            }
        }
    }

    @Test
    void event_with_no_covering_partition_is_rejected_loudly() throws SQLException {
        OffsetDateTime receivedAt = startOfDayUtc(currentUtcMonthStart().plusYears(5));
        OffsetDateTime occurredAt = receivedAt.minusHours(1);
        EventTablesTestSupport.Fixture owner = seedTenant();

        try (Connection connection = tenantConnection(owner.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertEvent(connection, owner, UUID.randomUUID(), occurredAt, receivedAt, "k"));

            assertEquals(SQLSTATE_CHECK_VIOLATION, rejected.getSQLState());
        }
    }

    // ---------- AC 6: outbox_events ----------

    @Test
    void outbox_has_the_required_columns_and_the_poller_index() throws SQLException {
        List<String> columns = columnNames("outbox_events");
        String indexes = String.join("\n", indexDefinitions("outbox_events"));

        assertTrue(columns.containsAll(List.of("status", "retry_count", "published_at", "event_received_at")));
        assertTrue(indexes.contains("idx_outbox_events_pending"));
        assertTrue(indexes.contains("WHERE") && indexes.contains("PENDING"));
    }

    @Test
    void outbox_row_gets_the_documented_defaults() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        UUID eventId = UUID.randomUUID();

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertOutbox(connection, owner, eventId, OffsetDateTime.now(ZoneOffset.UTC)));
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT status, retry_count, published_at IS NULL, created_at IS NOT NULL "
                            + "FROM outbox_events WHERE event_id = ?")) {
                statement.setObject(1, eventId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    assertEquals("PENDING", result.getString(1));
                    assertEquals(0, result.getInt(2));
                    assertTrue(result.getBoolean(3));
                    assertTrue(result.getBoolean(4));
                }
            }
        }
    }

    @Test
    void outbox_rejects_an_unknown_status_and_a_negative_retry_count() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = tenantConnection(owner.tenantId())) {
            insertOutbox(connection, owner, eventId, OffsetDateTime.now(ZoneOffset.UTC));
            SQLException badStatus = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE outbox_events SET status = 'BOGUS'");
                }
            });
            assertEquals(SQLSTATE_CHECK_VIOLATION, badStatus.getSQLState());
        }
        try (Connection connection = tenantConnection(owner.tenantId())) {
            insertOutbox(connection, owner, eventId, OffsetDateTime.now(ZoneOffset.UTC));
            SQLException badRetry = assertThrows(SQLException.class, () -> {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE outbox_events SET retry_count = -1");
                }
            });
            assertEquals(SQLSTATE_CHECK_VIOLATION, badRetry.getSQLState());
        }
    }

    // ---------- AC 7: idempotency_keys ----------

    @Test
    void idempotency_keys_has_the_composite_primary_key_and_the_expiry_column() throws SQLException {
        String definition = stringQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = 'public.idempotency_keys'::regclass AND contype = 'p'");
        String indexes = String.join("\n", indexDefinitions("idempotency_keys"));

        assertEquals("PRIMARY KEY (tenant_id, idempotency_key)", definition);
        assertTrue(columnNames("idempotency_keys").contains("expires_at"));
        assertTrue(indexes.contains("(expires_at)"));
    }

    @Test
    void a_duplicate_idempotency_key_for_the_same_tenant_is_rejected() throws SQLException {
        EventTablesTestSupport.Fixture owner = seedTenant();
        OffsetDateTime expiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);

        try (Connection connection = tenantConnection(owner.tenantId())) {
            assertEquals(1, insertIdempotencyKey(connection, owner.tenantId(), "dup", UUID.randomUUID(), expiresAt));

            SQLException duplicate = assertThrows(SQLException.class,
                    () -> insertIdempotencyKey(connection, owner.tenantId(), "dup", UUID.randomUUID(), expiresAt));

            assertEquals(SQLSTATE_UNIQUE_VIOLATION, duplicate.getSQLState());
        }
    }

    @Test
    void partition_names_are_not_empty_after_migration() throws SQLException {
        assertFalse(partitionNames().isEmpty());
    }
}