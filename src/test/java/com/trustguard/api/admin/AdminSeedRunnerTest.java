package com.trustguard.api.admin;

import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.api.admin.auth.AdminPasswordService;
import com.trustguard.api.admin.auth.AdminSecurityEventWriter;
import com.trustguard.api.admin.auth.AdminSeedRunner;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criteria 1, 2, 3 / RULING 24. The runner is constructed directly with different credentials against the
 * Testcontainers database. admin_users is emptied before each test; other admin tests seed their own
 * uniquely named admins, so this is safe as long as test classes run sequentially (the JUnit default).
 */
class AdminSeedRunnerTest extends AdminIntegrationTestBase {

    private static final String SEED_USERNAME = "seed-admin-marker-4711";
    private static final String SEED_PASSWORD = "seed-password-marker-4711";

    @Autowired
    private AdminPasswordService passwordService;

    @Autowired
    private AdminSecurityEventWriter eventWriter;
    // Invariant: this DELETE is safe only because every other admin test seeds its own uniquely named
    // admins and JUnit runs test classes sequentially. If parallel test execution is ever enabled, this
    // test class must be isolated (own container or own schema) first.
    @BeforeEach
    void emptyAdminTable() {
        JDBC.update("DELETE FROM admin_users");
    }

    private void runSeed(String username, String password, boolean required) {
        new AdminSeedRunner(JDBC, passwordService, eventWriter, username, password, required)
                .run(new DefaultApplicationArguments());
    }

    private static int adminCount() {
        return JDBC.queryForObject("SELECT count(*) FROM admin_users", Integer.class);
    }

    @Test
    void seed_creates_the_first_admin_with_a_cost_12_hash_and_is_idempotent() {
        runSeed(SEED_USERNAME, SEED_PASSWORD, true);

        assertEquals(1, adminCount());
        String hash = JDBC.queryForObject("SELECT password_hash FROM admin_users WHERE username = ?",
                String.class, SEED_USERNAME);
        UUID adminId = JDBC.queryForObject("SELECT id FROM admin_users WHERE username = ?", UUID.class,
                SEED_USERNAME);
        assertTrue(hash.contains("$12$"));
        assertTrue(passwordService.matches(SEED_PASSWORD, hash));
        assertEquals(1, JDBC.queryForObject("SELECT count(*) FROM security_events WHERE event_type = ? "
                        + "AND actor_id = ? AND details->>'source' = ?", Integer.class,
                AdminAuthConstants.EVENT_SEEDED, adminId.toString(), AdminAuthConstants.SOURCE_STARTUP_SEED));

        runSeed(SEED_USERNAME, SEED_PASSWORD, true);

        assertEquals(1, adminCount());
    }

    private static int seededEventCount() {
        return JDBC.queryForObject("SELECT count(*) FROM security_events WHERE event_type = ?", Integer.class,
                AdminAuthConstants.EVENT_SEEDED);
    }

    @Test
    void seed_does_nothing_when_an_admin_already_exists() {
        String existingAdmin = seedAdmin();
        int seededEventsBefore = seededEventCount();

        runSeed(SEED_USERNAME, SEED_PASSWORD, true);

        assertEquals(0, JDBC.queryForObject("SELECT count(*) FROM admin_users WHERE username = ?",
                Integer.class, SEED_USERNAME));
        assertEquals(1, JDBC.queryForObject("SELECT count(*) FROM admin_users WHERE username = ?",
                Integer.class, existingAdmin));
        assertEquals(seededEventsBefore, seededEventCount());
    }

    @Test
    void missing_credentials_only_warn_when_not_required() {
        runSeed("", "", false);

        assertEquals(0, adminCount());
    }

    @Test
    void missing_credentials_fail_startup_when_required() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> runSeed("", "", true));

        assertTrue(failure.getMessage().contains("ADMIN_SEED_USERNAME"));
        assertEquals(0, adminCount());
    }

    @Test
    void only_one_credential_supplied_fails_startup_even_when_not_required() {
        assertThrows(IllegalStateException.class, () -> runSeed(SEED_USERNAME, "", false));
        assertThrows(IllegalStateException.class, () -> runSeed("", SEED_PASSWORD, false));
        assertEquals(0, adminCount());
    }

    @Test
    void invalid_credentials_fail_startup_without_echoing_any_value() {
        String shortPassword = "tiny-marker";
        IllegalStateException tooShort = assertThrows(IllegalStateException.class,
                () -> runSeed(SEED_USERNAME, shortPassword, false));
        IllegalStateException sameAsUsername = assertThrows(IllegalStateException.class,
                () -> runSeed("same-marker-user", "SAME-MARKER-USER", false));

        assertFalse(tooShort.getMessage().contains(SEED_USERNAME));
        assertFalse(tooShort.getMessage().contains(shortPassword));
        assertFalse(sameAsUsername.getMessage().toLowerCase().contains("same-marker-user"));
        assertEquals(0, adminCount());
    }

    @Test
    void a_username_with_surrounding_whitespace_fails_startup_without_echoing_it() {
        IllegalStateException trailingCr = assertThrows(IllegalStateException.class,
                () -> runSeed(SEED_USERNAME + "\r", SEED_PASSWORD, false));
        IllegalStateException leadingSpace = assertThrows(IllegalStateException.class,
                () -> runSeed(" " + SEED_USERNAME, SEED_PASSWORD, false));

        assertTrue(trailingCr.getMessage().contains("ADMIN_SEED_USERNAME"));
        assertFalse(trailingCr.getMessage().contains(SEED_USERNAME));
        assertFalse(leadingSpace.getMessage().contains(SEED_USERNAME));
        assertEquals(0, adminCount());
    }

    @Test
    void seed_username_and_password_never_appear_in_logs() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            runSeed(SEED_USERNAME, SEED_PASSWORD, true);
            runSeed(SEED_USERNAME, SEED_PASSWORD, true);
            JDBC.update("DELETE FROM admin_users");
            assertThrows(IllegalStateException.class, () -> runSeed(SEED_USERNAME, "tiny-marker", true));

            String logged = appender.list.stream()
                    .map(event -> event.getFormattedMessage() + " "
                            + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .collect(Collectors.joining("\n"));

            assertFalse(logged.contains(SEED_PASSWORD));
            assertFalse(logged.contains(SEED_USERNAME));
        } finally {
            root.detachAppender(appender);
        }
    }
}