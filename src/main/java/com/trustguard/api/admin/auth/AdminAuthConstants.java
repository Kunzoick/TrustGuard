package com.trustguard.api.admin.auth;
import java.time.Duration;
/*
Named constants for admin authentication, Never instantiated
.
 */

public class AdminAuthConstants {
    public static final int BCRYPT_COST= 12;
    public static final Duration ACCESS_TOKEN_TTL= Duration.ofMinutes(60);
    public static final Duration MAX_SESSION_AGE= Duration.ofHours(8);
    public static final int MAX_FAILED_ATTEMPTS= 5;
    public static final Duration FAILURE_WINDOW= Duration.ofMinutes(15);
    public static final int MAX_PASSWORD_BYTES= 72;
    public static final int MIN_JWT_SECRET_BYTES= 32;
    public static final int MAX_ACTOR_ID_LENGTH= 255;
    public static final int MAX_IP_LENGTH= 45;

    public static final String ROLE_ADMIN= "ROLE_ADMIN";
    public static final String LOGIN_PATH= "/api/admin/auth/login";
    public static final String BEARER_PREFIX= "Bearer ";
    public static final String JWT_ALGORITHM= "HS256";
    public static final String CLAIM_ADMIN_ID= "adminId";
    public static final String CLAIM_TOKEN_VERSION= "tokenVersion";
    //used only to keep exactly one bcrypt verification on the over-length-password path
    public static final String DUMMY_PASSWORD= "constant-time-placeholder-passwprd";

    public static final String EVENT_LOGIN_FAILED= "ADMIN_LOGIN_FAILED";
    public static final String EVENT_ACCOUNT_LOCKED= "ADMIN_ACCOUNT_LOCKED";
    public static final String EVENT_LOGIN_SUCCEEDED = "ADMIN_LOGIN_SUCCEEDED";
    public static final String EVENT_LOGOUT= "ADMIN_LOGOUT";
    public static final String METRIC_EVENT_WRITE_FAILURE= "security_event.write.failure";
    public static final String METRIC_TAG_WRITER= "writer";
    public static final String METRIC_WRITER_ADMIN= "admin";

    private AdminAuthConstants(){}
}
