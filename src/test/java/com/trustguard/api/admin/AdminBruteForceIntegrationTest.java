package com.trustguard.api.admin;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criteria 3, 4 (timing), 10, 13, 14, 24, 26. Lock state lives only in PostgreSQL. Open-in-view is forced
 * on by the base class, so the concurrency test runs in the worst case (RULING 23).
 */
class AdminBruteForceIntegrationTest extends AdminIntegrationTestBase {

    private static final int SAMPLES_PER_KIND = 50;
    private static final int WARM_UP = 10;
    private static final double MAX_MEAN_DIFF_MS = 25.0;
    private static final String WRONG_PASSWORD = "wrong-password";

    private static Instant windowStart(String username) {
        return JDBC.queryForObject("SELECT failed_window_started_at FROM admin_users WHERE username = ?",
                Timestamp.class, username).toInstant();
    }

    @Test
    void five_failures_lock_the_account_and_correct_password_is_then_refused() throws Exception {
        String username = seedAdmin();

        for (int i = 0; i < 5; i++) {
            login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        assertTrue(isLocked(username));
        login(username, PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void failures_outside_the_fifteen_minute_window_start_a_new_window() throws Exception {
        String username = seedAdmin();
        for (int i = 0; i < 4; i++) {
            login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        }

        clock.advance(Duration.ofMinutes(16));
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        assertFalse(isLocked(username));
        assertEquals(1, failedCount(username));
    }

    @Test
    void failure_at_fourteen_minutes_59_seconds_stays_in_the_same_window() throws Exception {
        String username = seedAdmin();
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        Instant firstWindow = windowStart(username);

        clock.advance(Duration.ofMinutes(14).plusSeconds(59));
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        assertEquals(2, failedCount(username));
        assertEquals(firstWindow, windowStart(username));
    }

    @Test
    void failure_at_exactly_fifteen_minutes_starts_a_new_window() throws Exception {
        String username = seedAdmin();
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        Instant firstWindow = windowStart(username);

        clock.advance(Duration.ofMinutes(15));
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        assertEquals(1, failedCount(username));
        assertEquals(firstWindow.plus(Duration.ofMinutes(15)), windowStart(username));
    }

    @Test
    void failed_login_counter_and_event_are_committed_despite_the_401() throws Exception {
        String username = seedAdmin();

        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        assertEquals(1, failedCount(username));
        Integer events = JDBC.queryForObject(
                "SELECT count(*) FROM security_events WHERE event_type = 'ADMIN_LOGIN_FAILED' AND actor_id = ? "
                        + "AND ip_address IS NOT NULL AND created_at IS NOT NULL", Integer.class, username);
        assertEquals(1, events);
    }

    @Test
    void successful_login_resets_the_counter() throws Exception {
        String username = seedAdmin();
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
        login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());

        login(username, PASSWORD).andExpect(status().isOk());

        assertEquals(0, failedCount(username));
    }

    @Test
    void twenty_concurrent_wrong_passwords_lock_the_account_without_losing_counts() throws Exception {
        String username = seedAdmin();
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }
        executor.shutdown();

        assertTrue(isLocked(username));
        assertEquals(5, failedCount(username));
        UUID adminId = JDBC.queryForObject("SELECT id FROM admin_users WHERE username = ?", UUID.class, username);
        assertEquals(1, JDBC.queryForObject(
                "SELECT count(*) FROM security_events WHERE event_type = 'ADMIN_ACCOUNT_LOCKED' AND actor_id = ?",
                Integer.class, adminId.toString()));
        assertEquals(threads, JDBC.queryForObject(
                "SELECT count(*) FROM security_events WHERE event_type = 'ADMIN_LOGIN_FAILED' AND actor_id = ?",
                Integer.class, username));
    }

    @Test
    void wrong_password_locked_and_unknown_user_have_indistinguishable_timing() throws Exception {
        String valid = seedAdmin();
        String locked = seedAdmin();
        JDBC.update("UPDATE admin_users SET locked_at = now() WHERE username = ?", locked);
        List<String> order = new ArrayList<>();
        for (String kind : List.of("WRONG", "LOCKED", "UNKNOWN")) {
            for (int i = 0; i < SAMPLES_PER_KIND; i++) {
                order.add(kind);
            }
        }
        Collections.shuffle(order, new SecureRandom());
        Map<String, List<Long>> samples = new HashMap<>();

        for (String kind : order) {
            String username = switch (kind) {
                case "WRONG" -> valid;
                case "LOCKED" -> locked;
                default -> "no-such-" + UUID.randomUUID();
            };
            JDBC.update("UPDATE admin_users SET failed_attempt_count = 0, failed_window_started_at = NULL "
                    + "WHERE username = ?", valid);
            long begin = System.nanoTime();
            login(username, WRONG_PASSWORD).andExpect(status().isUnauthorized());
            samples.computeIfAbsent(kind, k -> new ArrayList<>()).add(System.nanoTime() - begin);
        }

        assertFalse(isLocked(valid), "the WRONG population must never reach the locked path");
        double wrong = meanMillis(samples.get("WRONG"));
        double lockedMean = meanMillis(samples.get("LOCKED"));
        double unknown = meanMillis(samples.get("UNKNOWN"));
        assertTrue(Math.abs(wrong - lockedMean) < MAX_MEAN_DIFF_MS, "wrong vs locked: " + wrong + " / " + lockedMean);
        assertTrue(Math.abs(wrong - unknown) < MAX_MEAN_DIFF_MS, "wrong vs unknown: " + wrong + " / " + unknown);
        assertTrue(Math.abs(lockedMean - unknown) < MAX_MEAN_DIFF_MS,
                "locked vs unknown: " + lockedMean + " / " + unknown);
    }

    private static double meanMillis(List<Long> nanos) {
        return nanos.stream().skip(WARM_UP).mapToLong(Long::longValue).average().orElseThrow() / 1_000_000.0;
    }
}