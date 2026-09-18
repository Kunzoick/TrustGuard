package com.trustguard.sdk.service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


/**
 * Rule 5.7 step 5. Redis revocation state is tracked in a single
 * global set — key name "revoked:global" — with each revoked keyId
 * as a member. Per Ruling A (B-006 consolidated feedback), this
 * resolves the earlier ambiguity in ADR-005's text: no tenant
 * prefix, and not one Redis key per keyId — one set, checked via
 * SISMEMBER.
 *
 * PostgreSQL fallback uses the bypass-role connection per ADR-006.
 *
 * Fail-closed per Rule 5.6 / Rule 9.5: any Redis exception falls
 * through to the PostgreSQL check. Revocation status is never
 * assumed "not revoked" just because Redis was unreachable.
 */
@Service
public class KeyRevocationService {
    private static final Logger log= LoggerFactory.getLogger(KeyRevocationService.class);
    private static final String REVOCATION_SET_KEY= "revoked:global";
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate authResolverJdbcTemplate;

    public KeyRevocationService(StringRedisTemplate redisTemplate, @Qualifier("authResolverJdbcTemplate")
    JdbcTemplate authResolverJdbcTemplate) {
        this.redisTemplate = redisTemplate;
        this.authResolverJdbcTemplate = authResolverJdbcTemplate;
    }
    public boolean isRevoked(String keyId){
        try{
            Boolean isMember= redisTemplate.opsForSet().isMember(REVOCATION_SET_KEY, keyId);
            if(isMember !=null){
                return isMember;
            }
        }catch (DataAccessException e){
            log.warn("Redis unavailable for key revocation check on keyId={}, "
                    + "falling back to PostgreSQL", maskKeyId(keyId), e);
        }
        return checkPostgresRevocation(keyId);
    }
    private boolean checkPostgresRevocation(String keyId){
        Integer count= authResolverJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM revoked_keys WHERE key_id = ?", Integer.class,
                keyId);
        return count !=null && count > 0;
    }
    private static String maskKeyId(String keyId){
        if(keyId== null || keyId.length() < 14){
            return "[REDACTED]";
        }
        return keyId.substring(0, 8) + "..." + keyId.substring(keyId.length() - 6);
    }
}