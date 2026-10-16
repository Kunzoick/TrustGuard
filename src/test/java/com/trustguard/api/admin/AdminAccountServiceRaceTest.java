package com.trustguard.api.admin;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.trustguard.api.admin.auth.AdminAccountService;
import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.api.admin.auth.AdminAuthenticationException;
import com.trustguard.api.admin.auth.AdminBruteForceService;
import com.trustguard.api.admin.auth.AdminPasswordService;
import com.trustguard.api.admin.auth.AdminSecurityEventWriter;
import com.trustguard.api.admin.repository.AdminLoginView;
import com.trustguard.api.admin.repository.AdminUser;
import com.trustguard.api.admin.repository.AdminUserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RULINGS 22, 29 and C: the under-lock re-check of the password change, tested deterministically. The
 * caller passes the bcrypt verification outside the transaction, then the locked row has changed (hash
 * replaced, or account locked). The change must be refused with 401 ADMIN_SESSION_EXPIRED, never touch the
 * entity and never write ADMIN_PASSWORD_CHANGED. Mocked repository and transaction manager, real policy.
 */
class AdminAccountServiceRaceTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String USERNAME = "race-admin";
    private static final String CURRENT_PASSWORD = "current-passphrase-2026";
    private static final String NEW_PASSWORD = "brand-new-passphrase-2026";
    private static final String CONCURRENT_PASSWORD = "changed-by-someone-else-1";
    private static final String IP_ADDRESS = "127.0.0.1";

    private final AdminPasswordService passwordService = new AdminPasswordService();
    private final AdminUserRepository repository = mock(AdminUserRepository.class);
    private final AdminBruteForceService bruteForceService = mock(AdminBruteForceService.class);
    private final AdminSecurityEventWriter eventWriter = mock(AdminSecurityEventWriter.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final AdminAccountService service = new AdminAccountService(repository, bruteForceService,
            passwordService, eventWriter, Clock.systemUTC(), transactionManager);

    private void stubVerifiedCaller(String hash) {
        AdminLoginView view = mock(AdminLoginView.class);
        when(view.getLockedAt()).thenReturn(null);
        when(view.getPasswordHash()).thenReturn(hash);
        when(view.getUsername()).thenReturn(USERNAME);
        when(repository.findLoginViewById(ADMIN_ID)).thenReturn(Optional.of(view));
    }

    private void assertRefusedAsSessionExpired(AdminUser lockedRow) {
        AdminAuthenticationException failure = assertThrows(AdminAuthenticationException.class,
                () -> service.changePassword(ADMIN_ID, CURRENT_PASSWORD, NEW_PASSWORD, IP_ADDRESS));

        Map<?, ?> error = (Map<?, ?>) failure.toResponseBody().get("error");
        assertEquals(AdminAuthConstants.HTTP_UNAUTHORIZED, failure.httpStatus());
        assertEquals("ADMIN_SESSION_EXPIRED", error.get("code"));
        verify(lockedRow, never()).changePassword(any(), any());
        verify(eventWriter, never()).recordBestEffort(eq(AdminAuthConstants.EVENT_PASSWORD_CHANGED), any(),
                any(), any());
    }

    @Test
    void a_hash_replaced_between_verify_and_lock_refuses_the_change() {
        String verifiedHash = passwordService.hash(CURRENT_PASSWORD);
        stubVerifiedCaller(verifiedHash);
        AdminUser lockedRow = mock(AdminUser.class);
        when(lockedRow.isLocked()).thenReturn(false);
        when(lockedRow.getPasswordHash()).thenReturn(passwordService.hash(CONCURRENT_PASSWORD));
        when(repository.findByIdForUpdate(ADMIN_ID)).thenReturn(Optional.of(lockedRow));

        assertRefusedAsSessionExpired(lockedRow);
    }

    @Test
    void an_account_locked_between_verify_and_lock_refuses_the_change() {
        String verifiedHash = passwordService.hash(CURRENT_PASSWORD);
        stubVerifiedCaller(verifiedHash);
        AdminUser lockedRow = mock(AdminUser.class);
        when(lockedRow.isLocked()).thenReturn(true);
        when(lockedRow.getPasswordHash()).thenReturn(verifiedHash);
        when(repository.findByIdForUpdate(ADMIN_ID)).thenReturn(Optional.of(lockedRow));

        assertRefusedAsSessionExpired(lockedRow);
    }
}