package com.trustguard.api.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.trustguard.api.admin.repository.AdminLoginView;
import com.trustguard.api.admin.repository.AdminUserRepository;
import com.trustguard.shared.enums.ErrorCode;

/**
 * Admin login and logout (Rule 16.7, N4). The login path is not wrapped in any transaction, so failure
 * state committed by AdminBruteForceService survives the 401. Exactly one bcrypt verification runs on
 * every login path before any branching. Every login failure is the same 401 (RULING D).
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int DUMMY_SECRET_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM= new SecureRandom();

    private final AdminUserRepository repository;
    private final AdminBruteForceService bruteForceService;
    private final AdminJwtService jwtService;
    private final AdminSecurityEventWriter eventWriter;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(AdminAuthConstants.BCRYPT_COST);
    private final String dummyHash;

    /**
     * Creates the service and a dummy cost-12 hash from SecureRandom bytes for unknown usernames.
     *
     * @param repository         admin repository
     * @param bruteForceService  lockout bookkeeping
     * @param jwtService         token issuer
     * @param eventWriter        security event writer
     * @param clock              time source
     * @param transactionManager the primary JPA transaction manager
     */
    public AdminAuthService(AdminUserRepository repository, AdminBruteForceService bruteForceService,
                            AdminJwtService jwtService, AdminSecurityEventWriter eventWriter, Clock clock,
                            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.bruteForceService = bruteForceService;
        this.jwtService = jwtService;
        this.eventWriter = eventWriter;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        byte[] random = new byte[DUMMY_SECRET_BYTES];
        SECURE_RANDOM.nextBytes(random);
        this.dummyHash = passwordEncoder.encode(HexFormat.of().formatHex(random));
    }

    /**
     * Authenticates an admin. The user is read through a closed projection (RULING 23) so no managed
     * AdminUser is left in the persistence context: with open-in-view on, the later PESSIMISTIC_WRITE
     * query could otherwise return that stale copy, and parallel guesses would each write count=1.
     *
     * @param username  submitted username
     * @param password  submitted password
     * @param ipAddress remote address (getRemoteAddr only)
     * @return the issued token and its lifetime
     */
    public LoginResult login(String username, String password, String ipAddress) {
        AdminLoginView admin = repository.findLoginViewByUsername(username).orElse(null);
        boolean tooLong = password.getBytes(StandardCharsets.UTF_8).length > AdminAuthConstants.MAX_PASSWORD_BYTES;
        String hashToCheck = admin != null ? admin.getPasswordHash() : dummyHash;
        String candidate = tooLong ? AdminAuthConstants.DUMMY_PASSWORD : password;
        boolean passwordMatches = passwordEncoder.matches(candidate, hashToCheck) && !tooLong;

        boolean exists = admin != null;
        boolean unlocked = exists && admin.getLockedAt() == null;
        if (exists && unlocked && passwordMatches) {
            return completeLogin(admin, ipAddress);
        }
        AdminBruteForceService.FailureReason reason = !exists
                ? AdminBruteForceService.FailureReason.UNKNOWN_USER
                : (!unlocked ? AdminBruteForceService.FailureReason.LOCKED
                : AdminBruteForceService.FailureReason.BAD_CREDENTIALS);
        bruteForceService.recordFailedAttempt(exists ? admin.getId() : null, username, ipAddress, reason);
        throw invalidCredentials();
    }

    /**
     * Invalidates every token of the caller (RULING B).
     *
     * @param adminId   caller's admin id
     * @param ipAddress remote address
     */
    public void logout(UUID adminId, String ipAddress) {
        Boolean updated = transactionTemplate.execute(status -> repository.findByIdForUpdate(adminId)
                .map(admin -> {
                    admin.incrementTokenVersion(clock.instant());
                    return true;
                })
                .orElse(false));
        if (Boolean.TRUE.equals(updated)) {
            eventWriter.recordBestEffort(AdminAuthConstants.EVENT_LOGOUT, adminId.toString(), ipAddress, Map.of());
        }
    }

    private LoginResult completeLogin(AdminLoginView admin, String ipAddress) {
        Optional<Integer> tokenVersion;
        try {
            tokenVersion = bruteForceService.registerSuccess(admin.getId(), ipAddress);
        } catch (RuntimeException e) {
            log.error("Failed to record a successful admin login; denying the login. adminId={}", admin.getId(), e);
            throw invalidCredentials();
        }
        if (tokenVersion.isEmpty()) {
            bruteForceService.recordFailedAttempt(admin.getId(), admin.getUsername(), ipAddress,
                    AdminBruteForceService.FailureReason.LOCKED);
            throw invalidCredentials();
        }
        String token = jwtService.issue(admin.getId(), tokenVersion.get());
        return new LoginResult(token, AdminAuthConstants.ACCESS_TOKEN_TTL.toSeconds());
    }

    private static AdminAuthenticationException invalidCredentials() {
        return new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED, HTTP_UNAUTHORIZED,
                "Invalid credentials.");
    }

    /**
     * Successful login result.
     *
     * @param token            signed JWT
     * @param expiresInSeconds token lifetime in seconds
     */
    public record LoginResult(String token, long expiresInSeconds) {
    }
}