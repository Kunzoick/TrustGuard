package com.trustguard.api.admin.auth;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trustguard.shared.enums.ErrorCode;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * issues and verifies admin JWTs. HS256 only. the secret has no default;
 * the constructor is the startup gate and never echoes the secret.
 */
@Service
public final class AdminJwtService {
    private static final int HTTP_UNAUTHORIZED= 401;
    private final SecretKey signingKey;
    private final Clock clock;
    /**
     * Creates the service and validates the secret.
     *
     * @param base64Secret base64 secret, decoded length at least 32 bytes
     * @param clock time source
     */
    public AdminJwtService(@Value("${trustguard.admin.jwt-secret}") String base64Secret, Clock clock){
        this.signingKey = Keys.hmacShaKeyFor(decodeSecret(base64Secret));
        this.clock = clock;
    }
    private static byte[] decodeSecret(String base64Secret){
        if(base64Secret== null || base64Secret.isBlank()){
            throw new IllegalStateException("ADMIN_JWT_SECRET is missing or blank. Set it to a base64 string " +
                    "that decodes to at least 32 bytes.");
        }
        byte[] decoded;
        try{
            decoded= Base64.getDecoder().decode(base64Secret.trim());
        }catch (IllegalArgumentException e){
            throw new IllegalStateException("ADMIN_JWT_SECRET is not valid base64.");
        }
        if(decoded.length < AdminAuthConstants.MIN_JWT_SECRET_BYTES){
            throw new IllegalStateException("ADMIN_JWT_SECRET must decode to at least "
                    + AdminAuthConstants.MIN_JWT_SECRET_BYTES +
                    " bytes (256 bits).");
        }
        return decoded;
    }
    /**
     * issues a token for the admin
     *
     * @param adminId adminId
     * @param tokenVersion current token_version
     * @return compact JWT
     */
    public String issue(UUID adminId, int tokenVersion){
        Instant now= clock.instant();
        return Jwts.builder().subject(adminId.toString()).claim(AdminAuthConstants.CLAIM_ADMIN_ID,
                        adminId.toString())
                .claim(AdminAuthConstants.CLAIM_TOKEN_VERSION, tokenVersion).issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(AdminAuthConstants.ACCESS_TOKEN_TTL)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
    /**
     * Verifies signature, algorithm and expiry
     * @param token compact JWT
     * @return verified claims
     */
    public AdminTokenClaims verify(String token){
        try{
            Jws<Claims> jws =  Jwts.parser().verifyWith(signingKey).clock(() -> Date.from(clock.instant())).build()
                    .parseSignedClaims(token);
            if(!AdminAuthConstants.JWT_ALGORITHM.equals(jws.getHeader().getAlgorithm())){
                throw invalidToken();
            }
            Claims claims = jws.getPayload();
            String adminId= claims.get(AdminAuthConstants.CLAIM_ADMIN_ID, String.class);
            Integer tokenVersion= claims.get(AdminAuthConstants.CLAIM_TOKEN_VERSION, Integer.class);
            if(adminId== null || tokenVersion== null || claims.getIssuedAt()== null || claims.getExpiration()== null){
                throw invalidToken();
            }
            return new AdminTokenClaims(UUID.fromString(adminId), tokenVersion, claims.getIssuedAt().toInstant());
        }catch (ExpiredJwtException e){
            throw new AdminAuthenticationException(ErrorCode.ADMIN_SESSION_EXPIRED, HTTP_UNAUTHORIZED,
                    "Admin session has expired.");
        }catch (JwtException | IllegalArgumentException e){
            throw invalidToken();
        }
    }
    private static AdminAuthenticationException invalidToken(){
        return new AdminAuthenticationException(ErrorCode.ADMIN_AUTHENTICATION_FAILED, HTTP_UNAUTHORIZED,
                "Invalid or missing admin token.");
    }
    /**
     * verified token claims
     *
     * @param adminId   admin id
     * @param tokenVerion token_version claim
     * @param issuesAt iat claim
     */
    public record AdminTokenClaims(UUID adminId, int tokenVersion, Instant issuedAt){
    }
}
