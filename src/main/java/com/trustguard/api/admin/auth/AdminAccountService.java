package com.trustguard.api.admin.auth;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.trustguard.api.admin.repository.AdminLoginView;
import com.trustguard.api.admin.repository.AdminUserRepository;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Admin account lifecycle: change own password and unlock another admin (Rule 16.7, RULINGS 22, 26, 29,
 * 31). No @Transactional (RULING 19, Rule 17.11): database work uses TransactionTemplate with a row lock.
 * bcrypt never runs inside a transaction, so it never holds the row lock.
 */
@Service
public class AdminAccountService {
    private static final String FIELD_NEW_PASSWORD= "newPassword";
    //private static final String FIELD_ADMIN_ID = "adminId";
    private static final String MESSAGE_VALIDATION_FAILED = "Request validation failed.";
    private static final String MESSAGE_INVALID_CREDENTIALS = "Invalid credentials.";
    private static final String MESSAGE_SESSION_EXPIRED = "Admin session has expired.";
    private static final String MESSAGE_ADMIN_NOT_FOUND = "Admin account not found.";

    private final AdminUserRepository repository;
    private final AdminBruteForceService bruteForceService;
    private final AdminPasswordService passwordService;
    private final AdminSecurityEventWriter eventWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the service.
     *
     * @param repository         admin repository
     * @param bruteForceService  shared lockout counter
     * @param passwordService    password policy and hashing
     * @param eventWriter        security event writer
     * @param clock              time source
     * @param transactionManager the primary JPA transaction manager
     */
    public AdminAccountService(AdminUserRepository repository, AdminBruteForceService bruteForceService,
                               AdminPasswordService passwordService, AdminSecurityEventWriter eventWriter,
                               Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.bruteForceService = bruteForceService;
        this.passwordService = passwordService;
        this.eventWriter = eventWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }
    /**
     * Changes the caller's own password. On success every token of the caller, including the one used for
     * this request, stops working (token_version is incremented).
     *
     * @param adminId         caller's admin id
     * @param currentPassword submitted current password
     * @param newPassword     proposed new password
     * @param ipAddress       remote address (getRemoteAddr only)
     */
    public void changePassword(UUID adminId, String currentPassword, String newPassword, String ipAddress) {
        AdminLoginView caller = repository.findLoginViewById(adminId)
                .orElseThrow(AdminAccountService::sessionExpired);
        if (caller.getLockedAt() != null) {
            throw sessionExpired();
        }
        String verifiedHash = caller.getPasswordHash();
        if (!passwordService.matches(currentPassword, verifiedHash)) {
            bruteForceService.recordFailedAttempt(adminId, adminId.toString(), ipAddress,
                    AdminAuthConstants.EVENT_PASSWORD_CHANGE_FAILED,
                    Map.of(AdminAuthConstants.DETAIL_REASON, AdminAuthConstants.REASON_BAD_CURRENT_PASSWORD));
            throw new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED,
                    AdminAuthConstants.HTTP_UNAUTHORIZED, MESSAGE_INVALID_CREDENTIALS);
        }
        List<String> violations = passwordService.violations(caller.getUsername(), newPassword, verifiedHash);
        if (!violations.isEmpty()) {
            throw policyViolation(violations);
        }
        String newHash = passwordService.hash(newPassword);
        boolean changed = Boolean.TRUE.equals(
                transactionTemplate.execute(status -> applyPasswordChange(adminId, verifiedHash, newHash)));
        if (!changed) {
            throw sessionExpired();
        }
        eventWriter.recordBestEffort(AdminAuthConstants.EVENT_PASSWORD_CHANGED, adminId.toString(), ipAddress,
                Map.of());
    }

    /**
     * Unlocks another admin (RULING 26). Does nothing if the target is not locked.
     *
     * @param callerId      authenticated admin performing the unlock
     * @param targetAdminId admin to unlock
     * @param ipAddress     remote address
     */
    public void unlock(UUID callerId, UUID targetAdminId, String ipAddress) {
        Boolean unlocked = transactionTemplate.execute(status -> repository.findByIdForUpdate(targetAdminId)
                .map(admin -> admin.unlock(clock.instant()))
                .orElse(null));
        if (unlocked == null) {
            throw new AdminAuthenticationException(ErrorCode.ADMIN_NOT_FOUND, AdminAuthConstants.HTTP_NOT_FOUND,
                    MESSAGE_ADMIN_NOT_FOUND);
        }
        if (unlocked) {
            eventWriter.recordBestEffort(AdminAuthConstants.EVENT_UNLOCKED, callerId.toString(), ipAddress,
                    Map.of(AdminAuthConstants.DETAIL_TARGET_ADMIN_ID, targetAdminId.toString()));
        }
    }

    private boolean applyPasswordChange(UUID adminId, String verifiedHash, String newHash) {
        return repository.findByIdForUpdate(adminId)
                .filter(admin -> !admin.isLocked())
                .filter(admin -> hashesEqual(admin.getPasswordHash(), verifiedHash))
                .map(admin -> {
                    admin.changePassword(newHash, clock.instant());
                    return true;
                })
                .orElse(false);
    }

    private static boolean hashesEqual(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static AdminAuthenticationException sessionExpired() {
        return new AdminAuthenticationException(ErrorCode.ADMIN_SESSION_EXPIRED,
                AdminAuthConstants.HTTP_UNAUTHORIZED, MESSAGE_SESSION_EXPIRED);
    }

    private static AdminAuthenticationException policyViolation(List<String> violations) {
        List<Map<String, String>> details = new ArrayList<>();
        for (String violation : violations) {
            details.add(AdminAuthenticationException.fieldDetail(FIELD_NEW_PASSWORD, violation));
        }
        return new AdminAuthenticationException(ErrorCode.VALIDATION_FAILED, AdminAuthConstants.HTTP_BAD_REQUEST,
                MESSAGE_VALIDATION_FAILED, details);
    }
}
