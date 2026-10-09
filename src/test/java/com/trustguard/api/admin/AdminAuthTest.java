package com.trustguard.api.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.tenant.context.TenantContextHolder;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

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
 * Criteria 1, 2, 6, 9, 18, 19, 24 plus RULINGS B, C, D, E. Goes through MockMvc so the real filter chain
 * runs. The TenantContextHolder import is test-only; the ArchUnit admin rules analyse production classes.
 */
class AdminAuthTest extends AdminIntegrationTestBase {

    private static SecretKey testKey() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(ADMIN_JWT_SECRET));
    }

    private static String forgedToken(String username, Instant issuedAt, Instant expiresAt) {
        UUID adminId = JDBC.queryForObject("SELECT id FROM admin_users WHERE username = ?", UUID.class, username);
        return Jwts.builder().subject(adminId.toString()).claim("adminId", adminId.toString())
                .claim("tokenVersion", 0)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(testKey(), Jwts.SIG.HS256).compact();
    }

    private ResultActions ping(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/probe/ping").header("Authorization", "Bearer " + token));
    }

    @Test
    void login_returns_a_sixty_minute_token() throws Exception {
        String username = seedAdmin();

        String body = login(username, PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(3600))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.token");
        Claims claims = Jwts.parser().verifyWith(testKey()).clock(() -> Date.from(clock.instant())).build()
                .parseSignedClaims(token).getPayload();

        assertEquals(3600, Duration.between(claims.getIssuedAt().toInstant(),
                claims.getExpiration().toInstant()).getSeconds());
    }

    @Test
    void stored_hash_uses_bcrypt_cost_12() {
        String username = seedAdmin();
        String hash = JDBC.queryForObject("SELECT password_hash FROM admin_users WHERE username = ?",
                String.class, username);

        assertTrue(hash.contains("$12$"));
        assertEquals(12, AdminAuthConstants.BCRYPT_COST);
    }

    @Test
    void wrong_password_unknown_user_and_locked_account_return_identical_401() throws Exception {
        String user = seedAdmin();
        String lockedUser = seedAdmin();
        JDBC.update("UPDATE admin_users SET locked_at = now() WHERE username = ?", lockedUser);

        String wrong = login(user, "wrong-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"))
                .andReturn().getResponse().getContentAsString();
        String unknown = login("no-such-" + UUID.randomUUID(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String locked = login(lockedUser, PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertEquals(wrong, unknown);
        assertEquals(wrong, locked);
    }

    @Test
    void password_longer_than_72_bytes_gets_the_generic_401() throws Exception {
        String username = seedAdmin();

        login(username, PASSWORD + "x".repeat(80)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
    }

    @Test
    void blank_username_returns_400_with_redacted_details() throws Exception {
        login("", PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].rejected").value("[REDACTED]"));
    }

    @Test
    void logout_returns_200_with_no_body_and_invalidates_all_tokens() throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);
        ping(token).andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(content().string(""));

        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        ping(loginForToken(username)).andExpect(status().isOk());
    }

    @Test
    void expired_token_returns_session_expired() throws Exception {
        String token = loginForToken(seedAdmin());

        clock.advance(Duration.ofMinutes(61));

        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
    }

    @Test
    void token_older_than_eight_hours_is_rejected_even_if_exp_is_in_the_future() throws Exception {
        String username = seedAdmin();
        Instant now = clock.instant();
        String token = forgedToken(username, now.minus(Duration.ofHours(9)), now.plus(Duration.ofHours(1)));

        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
    }

    @Test
    void eight_hour_age_limit_is_half_open_at_exactly_eight_hours() throws Exception {
        String username = seedAdmin();
        Instant issuedAt = clock.instant();
        String token = forgedToken(username, issuedAt, issuedAt.plus(Duration.ofHours(9)));

        clock.advance(Duration.ofHours(7).plusMinutes(59).plusSeconds(59));
        ping(token).andExpect(status().isOk());

        clock.advance(Duration.ofSeconds(1));
        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));

        clock.advance(Duration.ofSeconds(1));
        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
    }

    @Test
    void live_token_of_a_locked_admin_is_rejected_without_changing_token_version() throws Exception {
        String username = seedAdmin();
        String token = loginForToken(username);
        int versionBefore = JDBC.queryForObject("SELECT token_version FROM admin_users WHERE username = ?",
                Integer.class, username);

        JDBC.update("UPDATE admin_users SET locked_at = now() WHERE username = ?", username);

        ping(token).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_SESSION_EXPIRED"));
        assertEquals(versionBefore, JDBC.queryForObject("SELECT token_version FROM admin_users WHERE username = ?",
                Integer.class, username));
    }

    @Test
    void missing_or_garbage_token_returns_authentication_failed() throws Exception {
        mockMvc.perform(get("/api/admin/probe/ping")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
        ping("not-a-jwt").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("ADMIN_AUTHENTICATION_FAILED"));
    }

    @Test
    void full_admin_flow_works_with_no_tenant_context() throws Exception {
        String username = seedAdmin();
        assertNull(TenantContextHolder.get());

        String token = loginForToken(username);
        ping(token).andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        assertNull(TenantContextHolder.get());
    }

    @Test
    void jwt_password_and_secret_never_appear_in_logs() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        String wrongPassword = "wrong-password-marker-98765";
        try {
            String username = seedAdmin();
            login(username, wrongPassword).andExpect(status().isUnauthorized());
            String token = loginForToken(username);
            ping(token).andExpect(status().isOk());

            String logged = appender.list.stream()
                    .map(event -> event.getFormattedMessage() + " "
                            + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .collect(Collectors.joining("\n"));

            assertFalse(logged.contains(token));
            assertFalse(logged.contains(PASSWORD));
            assertFalse(logged.contains(wrongPassword));
            assertFalse(logged.contains(ADMIN_JWT_SECRET));
        } finally {
            root.detachAppender(appender);
        }
    }
}