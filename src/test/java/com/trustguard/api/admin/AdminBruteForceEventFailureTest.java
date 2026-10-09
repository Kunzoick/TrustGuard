package com.trustguard.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.api.admin.auth.AdminSecurityEventWriter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criteria 23 and 27 / RULING 22: with the security_events insert forced to fail, five failed logins
 * still lock the account, every response is still the generic 401, and the failure metric is incremented
 * once per forced failure (5 login-failed events plus 1 account-locked event).
 */
class AdminBruteForceEventFailureTest extends AdminIntegrationTestBase {

    private static final double EXPECTED_FAILED_WRITES = 6.0;

    @MockitoSpyBean
    private AdminSecurityEventWriter eventWriter;

    @Autowired
    private MeterRegistry meterRegistry;

    private double failureCount() {
        Counter counter = meterRegistry.find(AdminAuthConstants.METRIC_EVENT_WRITE_FAILURE)
                .tag(AdminAuthConstants.METRIC_TAG_WRITER, AdminAuthConstants.METRIC_WRITER_ADMIN)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void five_failed_logins_lock_the_account_even_when_event_inserts_fail() throws Exception {
        doThrow(new DataAccessResourceFailureException("forced failure"))
                .when(eventWriter).record(any(), any(), any(), any());
        double before = failureCount();
        String username = seedAdmin();

        for (int i = 0; i < 5; i++) {
            login(username, "wrong-password").andExpect(status().isUnauthorized());
        }

        assertTrue(isLocked(username));
        assertEquals(5, failedCount(username));
        assertEquals(EXPECTED_FAILED_WRITES, failureCount() - before);
    }
}