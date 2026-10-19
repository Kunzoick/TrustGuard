package com.trustguard.infrastructure.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static com.trustguard.infrastructure.db.EventTablesTestSupport.adminConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.currentUtcMonthStart;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertApiKeyRow;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertEvent;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.seedProject;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.seedTenant;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.tenantConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criterion 6 / RULINGS 47, 48: a trust_events or api_keys row can only reference a project of its
 * own tenant. Foreign key checks bypass RLS, so before V14 a tenant could reference another tenant's project.
 * The catalog checks do not depend on constraint names; the behavior checks run as trustguard_app.
 */
class TrustEventsProjectIntegrityIntegrationTest {

    private static final String SQLSTATE_FOREIGN_KEY_VIOLATION = "23503";

    /** Column lists, in declared order, of every foreign key on trust_events that references projects. */
    private static List<List<String>> foreignKeysToProjects() throws SQLException {
        List<List<String>> keys = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT (SELECT array_agg(a.attname::text ORDER BY k.ord) FROM unnest(c.conkey) "
                             + "WITH ORDINALITY AS k(attnum, ord) JOIN pg_attribute a "
                             + "ON a.attrelid = c.conrelid AND a.attnum = k.attnum) "
                             + "FROM pg_constraint c WHERE c.conrelid = 'public.trust_events'::regclass "
                             + "AND c.contype = 'f' AND c.confrelid = 'public.projects'::regclass")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    keys.add(List.of((String[]) result.getArray(1).getArray()));
                }
            }
        }
        return keys;
    }

    private static List<List<String>> uniqueKeysOnProjects() throws SQLException {
        List<List<String>> keys = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT (SELECT array_agg(a.attname::text ORDER BY k.ord) FROM unnest(c.conkey) "
                             + "WITH ORDINALITY AS k(attnum, ord) JOIN pg_attribute a "
                             + "ON a.attrelid = c.conrelid AND a.attnum = k.attnum) "
                             + "FROM pg_constraint c WHERE c.conrelid = 'public.projects'::regclass "
                             + "AND c.contype = 'u'")) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    keys.add(List.of((String[]) result.getArray(1).getArray()));
                }
            }
        }
        return keys;
    }

    /** Column lists of every foreign key on the given table, whatever it references. */
    private static List<List<String>> allForeignKeysOn(String table) throws SQLException {
        List<List<String>> keys = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT (SELECT array_agg(a.attname::text ORDER BY k.ord) FROM unnest(c.conkey) "
                             + "WITH ORDINALITY AS k(attnum, ord) JOIN pg_attribute a "
                             + "ON a.attrelid = c.conrelid AND a.attnum = k.attnum) "
                             + "FROM pg_constraint c WHERE c.conrelid = ?::regclass AND c.contype = 'f'")) {
            statement.setString(1, "public." + table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    keys.add(List.of((String[]) result.getArray(1).getArray()));
                }
            }
        }
        return keys;
    }

    private static OffsetDateTime receivedAt() throws SQLException {
        return currentUtcMonthStart().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    // ---------- catalog ----------

    @Test
    void exactly_one_foreign_key_references_projects_and_it_is_the_composite() throws SQLException {
        List<List<String>> keys = foreignKeysToProjects();

        assertEquals(1, keys.size());
        assertEquals(List.of("tenant_id", "project_id"), keys.get(0));
    }

    @Test
    void no_foreign_key_on_trust_events_has_project_id_as_its_sole_column() throws SQLException {
        for (List<String> columns : allForeignKeysOn("trust_events")) {
            assertNotEquals(List.of("project_id"), columns);
        }
    }

    @Test
    void projects_has_a_unique_constraint_on_tenant_id_and_id() throws SQLException {
        assertTrue(uniqueKeysOnProjects().contains(List.of("tenant_id", "id")));
    }

    @Test
    void api_keys_has_no_single_column_project_key_and_one_composite() throws SQLException {
        List<List<String>> keys = allForeignKeysOn("api_keys");

        for (List<String> columns : keys) {
            assertNotEquals(List.of("project_id"), columns);
        }
        assertTrue(keys.contains(List.of("tenant_id", "project_id")), keys.toString());
    }

    // ---------- behavior: trust_events ----------

    @Test
    void an_event_that_references_another_tenants_project_is_rejected() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        EventTablesTestSupport.Fixture mismatched = new EventTablesTestSupport.Fixture(a.tenantId(), b.projectId());
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(a.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertEvent(connection, mismatched,
                    UUID.randomUUID(), received.minusHours(1), received, "mismatched-project"));

            assertEquals(SQLSTATE_FOREIGN_KEY_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void an_event_that_references_its_own_tenants_project_is_accepted() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        UUID secondProject = seedProject(a.tenantId());
        OffsetDateTime received = receivedAt();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertEvent(connection, a, UUID.randomUUID(), received.minusHours(1), received,
                    "first-project"));
            EventTablesTestSupport.Fixture other = new EventTablesTestSupport.Fixture(a.tenantId(), secondProject);
            assertEquals(1, insertEvent(connection, other, UUID.randomUUID(), received.minusHours(1), received,
                    "second-project"));
        }
    }

    // ---------- behavior: api_keys ----------

    @Test
    void an_api_key_that_references_another_tenants_project_is_rejected() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();
        EventTablesTestSupport.Fixture mismatched = new EventTablesTestSupport.Fixture(a.tenantId(), b.projectId());

        try (Connection connection = tenantConnection(a.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertApiKeyRow(connection, mismatched));

            assertEquals(SQLSTATE_FOREIGN_KEY_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void an_api_key_that_references_its_own_tenants_project_is_accepted() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertApiKeyRow(connection, a));
        }
    }
}