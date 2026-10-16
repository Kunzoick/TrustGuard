package com.trustguard.api.admin.auth;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Admin password policy and hashing (Rulings 25 and 29). Policy: at least 12 characters, at most 72 UTF-8
 * bytes (bcrypt truncates silently beyond that), different from the username (case-insensitive) and
 * different from the current password. Violation messages never contain the submitted value.
 */
@Service
public class AdminPasswordService {
    private static final String MESSAGE_TOO_SHORT = "must be at least " + AdminAuthConstants.MIN_PASSWORD_LENGTH +
            " characters";
    private static final String MESSAGE_TOO_LONG = "must be at most " + AdminAuthConstants.MAX_PASSWORD_BYTES +
            " bytes in UTF-8";
    private static final String MESSAGE_EQUALS_USERNAME = "must not be the same as the username";
    private static final String MESSAGE_EQUALS_CURRENT = "must be different from the current password";
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(AdminAuthConstants.BCRYPT_COST);
    /**
     * Hashes a password with bcrypt cost 12. The caller must have checked the policy first.
     *
     * @param rawPassword password that already passed {@link #violations}
     * @return bcrypt hash
     */
    public String hash(String rawPassword){
        return encoder.encode(rawPassword);
    }
    /**
     * Verifies a password against a bcrypt hash. A password over 72 bytes never matches, and one bcrypt
     * verification still runs (against a placeholder), as in the login path.
     *
     * @param rawPassword submitted password
     * @param hash        stored bcrypt hash
     * @return true if the password matches
     */
    public boolean matches(String rawPassword, String hash){
        boolean tooLong= exceedsMaxBytes(rawPassword);
        String candidate= tooLong ? AdminAuthConstants.DUMMY_PASSWORD : rawPassword;
        return encoder.matches(candidate, hash) && !tooLong;
    }
    /**
     * Lists every policy violation of a proposed password. Empty means acceptable.
     *
     * @param username    the admin's username
     * @param newPassword proposed password
     * @param currentHash bcrypt hash of the current password, or null when there is none (seed)
     * @return violation messages, never containing the password
     */
    public List<String> violations(String username, String newPassword, String currentHash){
        List<String> violations= new ArrayList<>();
        if(newPassword.codePointCount(0, newPassword.length()) < AdminAuthConstants.MIN_PASSWORD_LENGTH){
            violations.add(MESSAGE_TOO_SHORT);
        }
        if (exceedsMaxBytes(newPassword)){
            violations.add(MESSAGE_TOO_LONG);
        }
        if(newPassword.equalsIgnoreCase(username)){
            violations.add(MESSAGE_EQUALS_USERNAME);
        }
        if (currentHash != null && matches(newPassword, currentHash)){
            violations.add(MESSAGE_EQUALS_CURRENT);
        }
        return violations;
    }
    private static boolean exceedsMaxBytes(String password){
        return password.getBytes(StandardCharsets.UTF_8).length > AdminAuthConstants.MAX_PASSWORD_BYTES;
    }
}
