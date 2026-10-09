package com.trustguard.api.admin;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

import com.trustguard.api.admin.auth.AdminAuthConstants;
import com.trustguard.api.admin.auth.AdminAuthenticationException;
import com.trustguard.api.admin.auth.AdminJwtService;
import com.trustguard.shared.enums.ErrorCode;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminJwtServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private static String randomSecret(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    void issued_token_round_trips_claims() {
        AdminJwtService service = new AdminJwtService(randomSecret(32), clockAt(T0));
        UUID adminId = UUID.randomUUID();

        AdminJwtService.AdminTokenClaims claims = service.verify(service.issue(adminId, 7));

        assertEquals(adminId, claims.adminId());
        assertEquals(7, claims.tokenVersion());
        assertEquals(T0, claims.issuedAt());
    }

    @Test
    void token_expires_after_sixty_minutes() {
        String secret = randomSecret(32);
        String token = new AdminJwtService(secret, clockAt(T0)).issue(UUID.randomUUID(), 0);
        AdminJwtService later = new AdminJwtService(secret, clockAt(T0.plus(Duration.ofMinutes(61))));

        AdminAuthenticationException e = assertThrows(AdminAuthenticationException.class, () -> later.verify(token));

        assertEquals(ErrorCode.ADMIN_SESSION_EXPIRED, e.code());
    }

    @Test
    void token_signed_with_another_key_is_rejected() {
        String token = new AdminJwtService(randomSecret(32), clockAt(T0)).issue(UUID.randomUUID(), 0);
        AdminJwtService other = new AdminJwtService(randomSecret(32), clockAt(T0));

        AdminAuthenticationException e = assertThrows(AdminAuthenticationException.class, () -> other.verify(token));

        assertEquals(ErrorCode.ADMIN_AUTHENTICATION_FAILED, e.code());
    }

    @Test
    void unsigned_and_hs384_tokens_are_rejected() {
        byte[] raw = new byte[64];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.getEncoder().encodeToString(raw);
        AdminJwtService service = new AdminJwtService(secret, clockAt(T0));
        SecretKey key = Keys.hmacShaKeyFor(raw);
        Date iat = Date.from(T0);
        Date exp = Date.from(T0.plus(Duration.ofMinutes(30)));
        String unsigned = Jwts.builder().subject("x").claim("adminId", UUID.randomUUID().toString())
                .claim("tokenVersion", 0).issuedAt(iat).expiration(exp).compact();
        String hs384 = Jwts.builder().subject("x").claim("adminId", UUID.randomUUID().toString())
                .claim("tokenVersion", 0).issuedAt(iat).expiration(exp).signWith(key, Jwts.SIG.HS384).compact();

        assertEquals(ErrorCode.ADMIN_AUTHENTICATION_FAILED,
                assertThrows(AdminAuthenticationException.class, () -> service.verify(unsigned)).code());
        assertEquals(ErrorCode.ADMIN_AUTHENTICATION_FAILED,
                assertThrows(AdminAuthenticationException.class, () -> service.verify(hs384)).code());
    }

    @Test
    void garbage_and_missing_claims_are_rejected() {
        String secret = randomSecret(32);
        AdminJwtService service = new AdminJwtService(secret, clockAt(T0));
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        String noClaims = Jwts.builder().subject("x").issuedAt(Date.from(T0))
                .expiration(Date.from(T0.plus(Duration.ofMinutes(30)))).signWith(key, Jwts.SIG.HS256).compact();

        assertThrows(AdminAuthenticationException.class, () -> service.verify("not-a-jwt"));
        assertThrows(AdminAuthenticationException.class, () -> service.verify(noClaims));
    }

    @Test
    void startup_gate_rejects_bad_secrets_without_echoing_them() {
        String shortSecret = randomSecret(16);
        String notBase64 = "this is !!! not base64 ***";

        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> new AdminJwtService(null, clockAt(T0)));
        IllegalStateException blank = assertThrows(IllegalStateException.class,
                () -> new AdminJwtService("   ", clockAt(T0)));
        IllegalStateException invalid = assertThrows(IllegalStateException.class,
                () -> new AdminJwtService(notBase64, clockAt(T0)));
        IllegalStateException tooShort = assertThrows(IllegalStateException.class,
                () -> new AdminJwtService(shortSecret, clockAt(T0)));

        assertFalse(invalid.getMessage().contains(notBase64));
        assertFalse(tooShort.getMessage().contains(shortSecret));
        assertEquals(true, missing.getMessage().contains("ADMIN_JWT_SECRET"));
        assertEquals(true, blank.getMessage().contains("ADMIN_JWT_SECRET"));
        assertEquals(60, AdminAuthConstants.ACCESS_TOKEN_TTL.toMinutes());
    }
}