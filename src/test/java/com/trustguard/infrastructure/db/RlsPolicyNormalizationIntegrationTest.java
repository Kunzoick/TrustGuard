package com.trustguard.infrastructure.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.trustguard.infrastructure.db.EventTablesTestSupport.adminConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.appConnection;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.count;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertApiKeyRow;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertProjectRow;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.insertRevokedKeyRow;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.seedTenant;
import static com.trustguard.infrastructure.db.EventTablesTestSupport.tenantConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Acceptance criteria 1-5 / RULINGS 44-46: after V14 each of projects, api_keys and revoked_keys has exactly
 * one policy in the NULLIF form with USING and WITH CHECK, V6's insert policies are gone, and isolation still
 * holds. Policy text is read as superuser; every behavior assertion runs as trustguard_app.
 */
class RlsPolicyNormalizationIntegrationTest {

    private static final String SQLSTATE_RLS_VIOLATION = "42501";
    private static final List<String> TABLES = List.of("projects", "api_keys", "revoked_keys");

    private record Policy(String name, String using, String withCheck) {
    }

    private static List<Policy> policiesOn(String table) throws SQLException {
        List<Policy> policies = new ArrayList<>();
        try (Connection admin = adminConnection();
             PreparedStatement statement = admin.prepareStatement(
                     "SELECT policyname, qual, with_check FROM pg_policies "
                             + "WHERE schemaname = 'public' AND tablename = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    policies.add(new Policy(result.getString(1), result.getString(2), result.getString(3)));
                }
            }
        }
        return policies;
    }

    private static void assertNoRowsVisible(Connection connection) throws SQLException {
        for (String table : TABLES) {
            assertEquals(0, count(connection, table), table);
        }
    }

    // ---------- AC 1, 2: the policy catalog ----------

    @Test
    void each_table_has_exactly_one_tenant_isolation_policy_in_the_nullif_form() throws SQLException {
        for (String table : TABLES) {
            List<Policy> policies = policiesOn(table);

            assertEquals(1, policies.size(), table);
            Policy policy = policies.get(0);
            assertEquals(table + "_tenant_isolation", policy.name());
            for (String expression : List.of(String.valueOf(policy.using()), String.valueOf(policy.withCheck()))) {
                assertTrue(expression.contains("app.tenant_id"), table + ": " + expression);
                assertTrue(expression.contains("NULLIF"), table + ": " + expression);
                assertTrue(expression.contains("uuid"), table + ": " + expression);
            }
            assertEquals(policy.using(), policy.withCheck(), table);
        }
    }

    @Test
    void the_v6_insert_policies_no_longer_exist() throws SQLException {
        for (String table : TABLES) {
            for (Policy policy : policiesOn(table)) {
                assertFalse(policy.name().endsWith("_insert_isolation"), policy.name());
            }
        }
    }

    // ---------- AC 3: cross-tenant insert rejected ----------

    @Test
    void a_project_owned_by_another_tenant_cannot_be_inserted() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertProjectRow(connection, b.tenantId()));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void an_api_key_owned_by_another_tenant_cannot_be_inserted() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class, () -> insertApiKeyRow(connection, b));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    @Test
    void a_revoked_key_owned_by_another_tenant_cannot_be_inserted() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            SQLException rejected = assertThrows(SQLException.class,
                    () -> insertRevokedKeyRow(connection, b.tenantId()));

            assertEquals(SQLSTATE_RLS_VIOLATION, rejected.getSQLState());
        }
    }

    // ---------- AC 4: same-tenant access still works ----------

    @Test
    void same_tenant_inserts_and_selects_work_and_other_tenants_see_nothing() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();
        EventTablesTestSupport.Fixture b = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(1, insertProjectRow(connection, a.tenantId()));
            assertEquals(1, insertApiKeyRow(connection, a));
            assertEquals(1, insertRevokedKeyRow(connection, a.tenantId()));
            connection.commit();
        }

        try (Connection connection = tenantConnection(a.tenantId())) {
            assertEquals(2, count(connection, "projects"));
            assertEquals(1, count(connection, "api_keys"));
            assertEquals(1, count(connection, "revoked_keys"));
        }
        try (Connection connection = tenantConnection(b.tenantId())) {
            assertEquals(1, count(connection, "projects"));
            assertEquals(0, count(connection, "api_keys"));
            assertEquals(0, count(connection, "revoked_keys"));
        }
    }

    // ---------- AC 5: reused connection ----------

    @Test
    void a_reused_connection_matches_no_rows_and_does_not_error() throws SQLException {
        EventTablesTestSupport.Fixture a = seedTenant();

        try (Connection connection = tenantConnection(a.tenantId())) {
            insertApiKeyRow(connection, a);
            insertRevokedKeyRow(connection, a.tenantId());
            // Positive control: inside the tenant transaction the rows tenant A owns are visible.
            assertTrue(count(connection, "projects") >= 1);
            assertTrue(count(connection, "api_keys") >= 1);
            assertTrue(count(connection, "revoked_keys") >= 1);
            connection.commit();

            // Same physical connection, now outside any tenant transaction. What the setting reads as here
            // (empty string or unset) is server behavior, so the natural case is checked first and the
            // empty-string case is then pinned explicitly below.
            connection.setAutoCommit(true);
            assertNoRowsVisible(connection);

            // The V2 policies raised an invalid uuid error on this value before V14.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', '', false)");
            }
            assertNoRowsVisible(connection);
        }
    }

    @Test
    void a_fresh_connection_with_no_tenant_context_sees_no_rows_and_does_not_error() throws SQLException {
        seedTenant();

        try (Connection connection = appConnection()) {
            assertNoRowsVisible(connection);
        }
    }
}