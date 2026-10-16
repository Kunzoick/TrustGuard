package com.trustguard.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import com.trustguard.api.admin.auth.AdminPasswordService;
import com.trustguard.api.admin.auth.AdminSecurityEventWriter;
import com.trustguard.api.admin.auth.AdminSeedRunner;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RULING 24: the lost-race path. Another instance inserted the same username between the EXISTS check and
 * the INSERT, so ON CONFLICT (username) DO NOTHING affects 0 rows. The runner must neither fail startup nor
 * write an ADMIN_SEEDED event. Mocked JdbcTemplate, real password policy.
 */
class AdminSeedRunnerLostRaceTest {

    private static final String SEED_USERNAME = "race-seed-admin";
    private static final String SEED_PASSWORD = "race-seed-passphrase-2026";

    @Test
    void losing_the_on_conflict_race_writes_no_event_and_does_not_fail() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AdminSecurityEventWriter eventWriter = mock(AdminSecurityEventWriter.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        AdminSeedRunner runner = new AdminSeedRunner(jdbcTemplate, new AdminPasswordService(), eventWriter,
                SEED_USERNAME, SEED_PASSWORD, true);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));

        verify(jdbcTemplate).update(anyString(), any(Object[].class));
        verify(eventWriter, never()).recordBestEffort(any(), any(), any(), any());
    }
}