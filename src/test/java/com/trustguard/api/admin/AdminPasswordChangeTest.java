package com.trustguard.api.admin;

import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.api.admin.auth.AdminSecurityEventWriter;
import com.trustguard.tenant.context.TenantContextHolder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criteria 4, 5, 6, 10, 12 / RULINGS 22, 29, 31, C. Goes through MockMvc so the real admin filter chain runs.
 */
class AdminPasswordChangeTest extends AdminIntegrationTestBase {

    private static final String NEW_PASSWORD = "brand-new-passphrase-2026";
    private static final String WRONG_PASSWORD = "wrong-current-marker-24680";
    private static final int FAILED_ATTEMPTS = 5;
    // One ADMIN_ACCOUNT_LOCKED event is written when the fifth failure locks the account.
    private static final int LOCK_EVENTS = 1;
    // Every audit write is forced to fail: 5 ADMIN_PASSWORD_CHANGE_FAILED writes + 1 ADMIN_ACCOUNT_LOCKED
    // write. The failure counter is read after the setup login, so the login event is not counted.
    private static final double EXPECTED_FAILED_WRITES = FAILED_ATTEMPTS + LOCK_EVENTS;

    @MockitoSpyBean
    private AdminSecurityEventWriter eventWriter;

    @Autowired
    private MeterRegistry meterRegistry;

    private ResultActions changePassword(String token, String current, String next) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/password")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .characterEncoding("UTF-8")
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private ResultActions ping(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/probe/ping").header("Authorization", "Bearer " + token));
    }

    private static UUID adminId(String username) {
        return JDBC.queryForObject("SELECT id FROM admin_users WHERE username = ?", UUID.class, username);
    }

    private static int tokenVersion(String username) {
        return JDBC.queryForObject("SELECT token_version FROM admin_users WHERE username = ?", Integer.class,
                username);
    }

    private static String passwordHash(String username) {
        return JDBC.queryForObject("SELECT password_hash FROM admin_users WHERE username = ?", String.class,
                username);
    }

    private static int eventCount(String eventType, UUID actorId) {
        return JDBC.queryForObject("SELECT count(*) FROM security_events WHERE event_type = ? AND actor_id = ?",
                Integer.class, eventType, actorId.toString());
    }

    private double failureCount() {
        Counter counter = meterRegistry.find(AdminAuthConstants.METRIC_EVENT_WRITE_FAILURE)
                .tag(AdminAuthConstants.METRIC_TAG_WRITER, AdminAuthConstants.METRIC_WRITER_ADMIN)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void assertPolicyRejected(String newPassword) throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);
        String hashBefore = passwordHash(username);

        String body = changePassword(token, PASSWORD, newPassword).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("newPassword"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(newPassword));
        assertEquals(hashBefore, passwordHash(username));
        assertEquals(0, failedCount(username));
        ping(token).andExpect(status().isOk());
    }

    @Test
    void successful_change_updates_hash_kills_the_token_and_resets_counters() throws Exception {
        String username = seedAdmin();
        UUID id = adminId(username);
        String token = loginForToken(username);
        login(username, "wrong-password").andExpect(status().isUnauthorized());
        assertEquals(1, failedCount(username));
        int versionBefore = tokenVersion(username);
        String hashBefore = passwordHash(username);
        assertNull(TenantContextHolder.get());

        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk())
                .andExpect(content().string(""));

        assertNull(TenantContextHolder.get());
        assertEquals(versionBefore + 1, tokenVersion(username));
        assertEquals(0, failedCount(username));
        assertNotEquals(hashBefore, passwordHash(username));
        assertTrue(passwordHash(username).contains("$12$"));
        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        login(username, NEW_PASSWORD).andExpect(status().isOk());
        login(username, PASSWORD).andExpect(status().isUnauthorized());
        assertEquals(1, eventCount(AdminAuthConstants.EVENT_PASSWORD_CHANGED, id));
    }

    @Test
    void five_wrong_current_passwords_lock_the_account_and_write_the_ruling_31_events() throws Exception {
        String username = seedAdmin();
        UUID id = adminId(username);
        String token = loginForToken(username);

        for (int attempt = 1; attempt <= 4; attempt++) {
            changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
        }
        assertFalse(isLocked(username));
        assertEquals(4, failedCount(username));

        changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));

        assertTrue(isLocked(username));
        assertEquals(5, eventCount(AdminAuthConstants.EVENT_PASSWORD_CHANGE_FAILED, id));
        assertEquals(1, eventCount(AdminAuthConstants.EVENT_ACCOUNT_LOCKED, id));
        changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        login(username, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void wrong_current_passwords_share_the_counter_with_failed_logins() throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);

        for (int attempt = 1; attempt <= 3; attempt++) {
            login(username, "wrong-password").andExpect(status().isUnauthorized());
        }
        assertEquals(3, failedCount(username));
        changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized());
        assertFalse(isLocked(username));
        changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized());

        assertTrue(isLocked(username));
    }

    @Test
    void a_current_password_over_72_bytes_gets_the_generic_401_and_counts() throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);

        changePassword(token, PASSWORD + "x".repeat(80), NEW_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));

        assertEquals(1, failedCount(username));
    }

    @Test
    void a_password_shorter_than_12_characters_is_rejected() throws Exception {
        assertPolicyRejected("short-pw1");
    }

    @Test
    void a_password_over_72_bytes_is_rejected_by_byte_count_not_character_count() throws Exception {
        assertPolicyRejected("\u00e9".repeat(40));
    }

    @Test
    void a_password_equal_to_the_username_is_rejected_case_insensitively() throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);

        changePassword(token, PASSWORD, username.toUpperCase()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"));

        ping(token).andExpect(status().isOk());
    }

    @Test
    void a_password_equal_to_the_current_one_is_rejected() throws Exception {
        assertPolicyRejected(PASSWORD);
    }

    @Test
    void blank_new_password_and_oversized_current_password_return_400_with_redacted_details() throws Exception {
        String token = loginForToken(seedAdmin());

        changePassword(token, PASSWORD, "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"));
        changePassword(token, "x".repeat(1001), NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"));
    }

    @Test
    void unauthenticated_request_is_rejected() throws Exception {
        mockMvc.perform(post("/api/admin/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"a\",\"newPassword\":\"b\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
    }

    @Test
    void passwords_never_appear_in_logs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String username = seedAdmin();
            String token = loginForToken(username);
            changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized());
            changePassword(token, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

            String logged = appender.list.stream()
                    .map(event -> event.getFormattedMessage() + " "
                            + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .collect(Collectors.joining("\n"));

            assertFalse(logged.contains(WRONG_PASSWORD));
            assertFalse(logged.contains(NEW_PASSWORD));
            assertFalse(logged.contains(PASSWORD));
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void failed_audit_writes_never_change_the_counter_or_the_401() throws Exception {
        doThrow(new DataAccessResourceFailureException("forced failure"))
                .when(eventWriter).record(any(), any(), any(), any());
        String username = seedAdmin();
        String token = loginForToken(username);
        double before = failureCount();

        for (int attempt = 1; attempt <= FAILED_ATTEMPTS; attempt++) {
            changePassword(token, WRONG_PASSWORD, NEW_PASSWORD).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
        }

        assertTrue(isLocked(username));
        assertEquals(5, failedCount(username));
        assertEquals(EXPECTED_FAILED_WRITES, failureCount() - before);
    }
}