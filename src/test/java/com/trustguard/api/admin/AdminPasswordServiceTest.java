package com.trustguard.api.admin;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.trustguard.api.admin.auth.AdminPasswordService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criterion 6 / RULINGS 25 and 29: the password policy. Pure unit test, no Spring context.
 */
class AdminPasswordServiceTest {

    private static final String USERNAME = "platform-admin";
    private static final String GOOD_PASSWORD = "correct-horse-battery-staple";
    private static final String TWO_BYTE_CHAR = "\u00e9";
    private static final int MAX_BYTES = 72;

    private final AdminPasswordService service = new AdminPasswordService();

    @Test
    void a_valid_password_has_no_violations() {
        assertTrue(service.violations(USERNAME, GOOD_PASSWORD, null).isEmpty());
    }

    @Test
    void eleven_characters_are_rejected_and_twelve_are_accepted() {
        List<String> tooShort = service.violations(USERNAME, "a".repeat(11), null);

        assertEquals(1, tooShort.size());
        assertTrue(tooShort.get(0).contains("at least 12"));
        assertTrue(service.violations(USERNAME, "a".repeat(12), null).isEmpty());
    }

    @Test
    void ascii_byte_limit_is_72_inclusive() {
        assertTrue(service.violations(USERNAME, "a".repeat(MAX_BYTES), null).isEmpty());

        List<String> tooLong = service.violations(USERNAME, "a".repeat(MAX_BYTES + 1), null);

        assertEquals(1, tooLong.size());
        assertTrue(tooLong.get(0).contains("72 bytes"));
    }

    @Test
    void the_limit_counts_utf8_bytes_not_characters() {
        // 36 two-byte characters are exactly 72 bytes; 37 are 74 bytes although only 37 characters.
        assertTrue(service.violations(USERNAME, TWO_BYTE_CHAR.repeat(36), null).isEmpty());
        assertEquals(1, service.violations(USERNAME, TWO_BYTE_CHAR.repeat(37), null).size());
        assertEquals(1, service.violations(USERNAME, TWO_BYTE_CHAR.repeat(40), null).size());
    }

    @Test
    void password_equal_to_username_is_rejected_case_insensitively() {
        List<String> violations = service.violations("Platform-Admin-01", "platform-admin-01", null);

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("username"));
    }

    @Test
    void password_equal_to_current_is_rejected_and_a_different_one_is_not() {
        String currentHash = service.hash(GOOD_PASSWORD);

        List<String> same = service.violations(USERNAME, GOOD_PASSWORD, currentHash);

        assertEquals(1, same.size());
        assertTrue(same.get(0).contains("current password"));
        assertTrue(service.violations(USERNAME, "another-long-passphrase", currentHash).isEmpty());
    }

    @Test
    void violation_messages_never_contain_the_submitted_value() {
        String secret = "secret1";

        List<String> violations = service.violations(secret, secret, null);

        assertEquals(2, violations.size());
        for (String violation : violations) {
            assertFalse(violation.contains(secret));
        }
    }

    @Test
    void matches_rejects_a_password_over_72_bytes_even_when_bcrypt_would_truncate_it_to_a_match() {
        String hash = service.hash("a".repeat(MAX_BYTES));

        assertTrue(service.matches("a".repeat(MAX_BYTES), hash));
        assertFalse(service.matches("a".repeat(MAX_BYTES + 1), hash));
    }

    @Test
    void hash_is_bcrypt_cost_12() {
        String hash = service.hash(GOOD_PASSWORD);

        assertTrue(hash.startsWith("$2"));
        assertTrue(hash.contains("$12$"));
    }
}