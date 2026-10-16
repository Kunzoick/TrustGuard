package com.trustguard.api.admin.auth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the first platform admin on an empty admin_users table (RULING 24). The username and the
 * password are never logged and never echoed in an exception message. Startup is refused when supplied
 * credentials are invalid, and (only with trustguard.admin.seed.required=true) when the table is empty
 * and no credentials are supplied.
 */
@Component
public class AdminSeedRunner implements ApplicationRunner{
    private static final Logger log= LoggerFactory.getLogger(AdminSeedRunner.class);
    private static final String ENV_USERNAME= "ADMIN_SEED_USERNAME";
    private static final String ENV_PASSWORD= "ADMIN_SEED_PASSWORD";

    private final JdbcTemplate jdbcTemplate;
    private final AdminPasswordService passwordService;
    private final AdminSecurityEventWriter eventWriter;
    private final String seedUsername;
    private final String seedPassword;
    private final boolean seedRequired;
    /**
     * Creates the runner. The first parameter name must stay jdbcTemplate: it disambiguates the primary
     * JdbcTemplate from the auth-resolver one.
     *
     * @param jdbcTemplate    primary (trustguard_app) template
     * @param passwordService policy and hashing
     * @param eventWriter     security event writer
     * @param seedUsername    seed username, empty means absent
     * @param seedPassword    seed password, empty means absent
     * @param seedRequired    whether an empty table without credentials must fail startup
     */
    public AdminSeedRunner(JdbcTemplate jdbcTemplate, AdminPasswordService passwordService,
                           AdminSecurityEventWriter eventWriter,
                           @Value("${trustguard.admin.seed.username:}") String seedUsername,
                           @Value("${trustguard.admin.seed.password:}") String seedPassword,
                           @Value("${trustguard.admin.seed.required:false}") boolean seedRequired){
        this.jdbcTemplate= jdbcTemplate;
        this.passwordService= passwordService;
        this.eventWriter= eventWriter;
        this.seedUsername= seedUsername;
        this.seedPassword= seedPassword;
        this.seedRequired= seedRequired;
    }
    @Override
    public void run(ApplicationArguments args) {
        //V1 single-instance only. Multi-instance requires distributed locking around this.
        boolean adminExist= Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("SELECT EXISTS (SELECT 1 FROM admin_users)", Boolean.class));
        if(adminExist){
            log.info("Admin seed skipped: admin_users is not empty.");
            return;
        }
        boolean usernameSupplied= seedUsername != null && !seedUsername.isBlank();
        boolean passwordSupplied= seedPassword != null && !seedPassword.isBlank();
        if(!usernameSupplied && !passwordSupplied){
            handleMissingCredentials();
            return;
        }
        if(!usernameSupplied || !passwordSupplied){
            throw new IllegalStateException(ENV_USERNAME+ " and "+
                    ENV_PASSWORD + " must both be set; only one is set.");
        }
        validateCredentials();
        createAdmin();
    }
    private void handleMissingCredentials(){
        if(seedRequired){
            throw new IllegalStateException("admin_users is empty and no seed credentials are set. Set "
                    + ENV_USERNAME + " and " + ENV_PASSWORD + " to create the first admin.");
        }
        log.warn("admin_users is empty and no seed credentials are set; no admin was created. Set {} and {}.",
                ENV_USERNAME, ENV_PASSWORD);
    }
    private void validateCredentials(){
        if(!seedUsername.equals(seedUsername.strip())){
            throw new IllegalStateException(ENV_USERNAME + " must not contain whitespace.");
        }
        if(seedUsername.length() > AdminAuthConstants.MAX_USERNAME_LENGTH){
            throw new IllegalStateException(ENV_USERNAME + " must be at most "
                    + AdminAuthConstants.MAX_USERNAME_LENGTH + " characters.");
        }
        List<String> violations= passwordService.violations(seedUsername, seedPassword, null);
            if(!violations.isEmpty()){
                throw new IllegalStateException(ENV_PASSWORD + " does not satisfy the password policy: "
                        + String.join("; ", violations) + ".");
            }
    }
    private void createAdmin(){
        UUID adminId= UUID.randomUUID();
        String passwordHash= passwordService.hash(seedPassword);
        int inserted = jdbcTemplate.update("INSERT INTO admin_users (id, username, password_hash) "
                + "VALUES (?, ?, ?) ON CONFLICT (username) DO NOTHING", adminId, seedUsername, passwordHash);
        if(inserted== 1){
            log.info("First admin created from the seed credentials. adminId={}", adminId);
            eventWriter.recordBestEffort(AdminAuthConstants.EVENT_SEEDED, adminId.toString(), null,
                    Map.of(AdminAuthConstants.DETAIL_SOURCE, AdminAuthConstants.SOURCE_STARTUP_SEED));
        }else{
            log.info("Admin seed skipped: an admin with seed username already exists.");
        }
    }
}
