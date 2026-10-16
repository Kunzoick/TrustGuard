package com.trustguard.api.admin;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.tenant.context.TenantContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criteria 7, 8, 10 / RULINGS 26, 28, 32, C. A second admin is created through the JDBC seedAdmin() helper
 * (there is no create-admin endpoint, RULING 28) and the target is locked with direct SQL.
 */
class AdminUnlockTest extends AdminIntegrationTestBase {

    private ResultActions unlock(String callerToken, String targetId) throws Exception {
        return mockMvc.perform(post("/api/admin/admins/" + targetId + "/unlock")
                .header("Authorization", "Bearer " + callerToken));
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

    private static void lock(String username) {
        JDBC.update("UPDATE admin_users SET locked_at = now(), failed_attempt_count = 5, "
                + "failed_window_started_at = now() WHERE username = ?", username);
    }

    private static int unlockEventCount(UUID callerId, UUID targetId) {
        return JDBC.queryForObject("SELECT count(*) FROM security_events WHERE event_type = ? AND actor_id = ? "
                        + "AND details->>'targetAdminId' = ?", Integer.class, AdminAuthConstants.EVENT_UNLOCKED,
                callerId.toString(), targetId.toString());
    }

    @Test
    void another_admin_unlocks_a_locked_admin_and_the_old_token_stays_dead() throws Exception {
        String callerName = seedAdmin();
        String targetName = seedAdmin();
        UUID callerId = adminId(callerName);
        UUID targetId = adminId(targetName);
        String callerToken = loginForToken(callerName);
        String targetToken = loginForToken(targetName);
        lock(targetName);
        ping(targetToken).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        int versionBefore = tokenVersion(targetName);
        assertNull(TenantContextHolder.get());

        unlock(callerToken, targetId.toString()).andExpect(status().isOk()).andExpect(content().string(""));

        assertNull(TenantContextHolder.get());
        assertFalse(isLocked(targetName));
        assertEquals(0, failedCount(targetName));
        assertTrue(JDBC.queryForObject("SELECT failed_window_started_at IS NULL FROM admin_users "
                + "WHERE username = ?", Boolean.class, targetName));
        assertEquals(versionBefore + 1, tokenVersion(targetName));
        ping(targetToken).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        ping(loginForToken(targetName)).andExpect(status().isOk());
        assertEquals(1, unlockEventCount(callerId, targetId));
    }

    @Test
    void unlocking_an_admin_that_is_not_locked_is_a_no_op_with_no_event() throws Exception {
        String callerName = seedAdmin();
        String targetName = seedAdmin();
        UUID callerId = adminId(callerName);
        UUID targetId = adminId(targetName);
        String callerToken = loginForToken(callerName);
        String targetToken = loginForToken(targetName);
        int versionBefore = tokenVersion(targetName);

        unlock(callerToken, targetId.toString()).andExpect(status().isOk()).andExpect(content().string(""));

        assertEquals(versionBefore, tokenVersion(targetName));
        assertEquals(0, unlockEventCount(callerId, targetId));
        ping(targetToken).andExpect(status().isOk());
    }

    @Test
    void unknown_admin_returns_404_admin_not_found() throws Exception {
        String callerToken = loginForToken(seedAdmin());

        unlock(callerToken, UUID.randomUUID().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("ADMIN_NOT_FOUND"));
    }

    @Test
    void malformed_admin_id_returns_400_validation_failed() throws Exception {
        String callerToken = loginForToken(seedAdmin());

        unlock(callerToken, "not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"));
    }

    @Test
    void unauthenticated_request_is_rejected() throws Exception {
        mockMvc.perform(post("/api/admin/admins/" + UUID.randomUUID() + "/unlock"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
    }

    @Test
    void a_locked_caller_cannot_use_unlock_or_password_change() throws Exception {
        String callerName = seedAdmin();
        String targetName = seedAdmin();
        String callerToken = loginForToken(callerName);
        lock(targetName);
        lock(callerName);

        unlock(callerToken, adminId(targetName).toString()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        String passwordChangeJson = "{\"currentPassword\":\"" + PASSWORD
                + "\",\"newPassword\":\"another-passphrase-1\"}";
        mockMvc.perform(post("/api/admin/auth/password")
                        .header("Authorization", "Bearer " + callerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordChangeJson))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));

        assertTrue(isLocked(targetName));
    }
}