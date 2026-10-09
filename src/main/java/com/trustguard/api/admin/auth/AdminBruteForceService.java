package com.trustguard.api.admin.auth;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.trustguard.api.admin.repository.AdminUserRepository;

/**
 * Lockout bookkeeping (Rule 16.7, N5, RULINGS 19 and 22). No @Transactional anywhere: the counter
 * commits in its own REQUIRES_NEW TransactionTemplate transaction FIRST; the security event is written
 * afterwards in a separate transaction. A bookkeeping failure is logged and never changes the 401.
 */
@Service
public class AdminBruteForceService {

    private static final Logger log = LoggerFactory.getLogger(AdminBruteForceService.class);

    /** Internal failure reasons for operators; never returned to clients. */
    public enum FailureReason { BAD_CREDENTIALS, LOCKED, UNKNOWN_USER }

    private final AdminUserRepository repository;
    private final AdminSecurityEventWriter eventWriter;
    private final Clock clock;
    private final TransactionTemplate requiresNewTemplate;

    /**
     * Creates the service.
     *
     * @param repository         admin repository
     * @param eventWriter        security event writer
     * @param clock              time source
     * @param transactionManager the primary JPA transaction manager
     */
    public AdminBruteForceService(AdminUserRepository repository, AdminSecurityEventWriter eventWriter,
                                  Clock clock, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.eventWriter = eventWriter;
        this.clock = clock;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
    }

    /**
     * Records a failed login. Never throws.
     *
     * @param adminId   admin id, or null for an unknown username (no counter applies)
     * @param username  submitted username
     * @param ipAddress remote address
     * @param reason    internal reason
     */
    public void recordFailedAttempt(UUID adminId, String username, String ipAddress, FailureReason reason) {
        boolean lockedNow = false;
        if (adminId != null) {
            try {
                lockedNow = Boolean.TRUE.equals(requiresNewTemplate.execute(status -> applyFailure(adminId)));
            } catch (RuntimeException e) {
                log.error("Failed to persist the failed-login counter. adminId={}", adminId, e);
            }
        }
        eventWriter.recordBestEffort(AdminAuthConstants.EVENT_LOGIN_FAILED, username, ipAddress,
                Map.of("reason", reason.name()));
        if (lockedNow) {
            eventWriter.recordBestEffort(AdminAuthConstants.EVENT_ACCOUNT_LOCKED, adminId.toString(), ipAddress,
                    Map.of("failedAttempts", AdminAuthConstants.MAX_FAILED_ATTEMPTS));
        }
    }

    /**
     * Completes a successful login under the row lock (N4 step 5).
     *
     * @param adminId   admin id
     * @param ipAddress remote address
     * @return the current token_version, or empty if the account was locked concurrently
     */
    public Optional<Integer> registerSuccess(UUID adminId, String ipAddress) {
        Integer tokenVersion = requiresNewTemplate.execute(status -> repository.findByIdForUpdate(adminId)
                .filter(admin -> !admin.isLocked())
                .map(admin -> {
                    admin.registerSuccess(clock.instant());
                    return admin.getTokenVersion();
                })
                .orElse(null));
        if (tokenVersion != null) {
            eventWriter.recordBestEffort(AdminAuthConstants.EVENT_LOGIN_SUCCEEDED, adminId.toString(), ipAddress,
                    Map.of());
        }
        return Optional.ofNullable(tokenVersion);
    }

    private boolean applyFailure(UUID adminId) {
        return repository.findByIdForUpdate(adminId)
                .map(admin -> admin.registerFailure(clock.instant(), AdminAuthConstants.MAX_FAILED_ATTEMPTS,
                        AdminAuthConstants.FAILURE_WINDOW))
                .orElse(false);
    }
}