package com.trustguard.sdk.service;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import com.trustguard.shared.domain.ProjectId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.shared.enums.Capability;
import com.trustguard.shared.enums.Environment;
import com.trustguard.tenant.context.TenantContext;

import tools.jackson.databind.ObjectMapper;
/**
 * Rule 5.7 step 6 — resolves TenantContext from an opaque keyId.
 * Redis key format is apikey:{keyId} — no tenant prefix, per
 * ADR-005: tenant identity is the output of this lookup, not an
 * available input.
 *
 * PostgreSQL fallback uses the authResolverJdbcTemplate
 * (trustguard_authresolver role, BYPASSRLS) per ADR-006 — the
 * RLS-protected ApiKeyRepository cannot be used here because no
 * TenantContext exists yet to populate app.tenant_id, so its
 * queries would return empty for every key.
 */
@Service
public class KeyLookupService {
    private static final String CACHE_KEY_PREFIX = "apikey:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Logger log= LoggerFactory.getLogger(KeyLookupService.class);

    private static final RowMapper<ApiKeyLookupRow> ROW_MAPPER = (rs, rowNum) -> new ApiKeyLookupRow(
            rs.getString("key_id"), UUID.fromString(rs.getString("tenant_id")), UUID.fromString(rs.getString("project_id")),
            rs.getString("environment"), rs.getString("capabilities") == null ? new String[0] :
            (String[]) rs.getArray("capabilities").getArray());
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate authResolverJdbcTemplate;
    private final ObjectMapper objectMapper;

    public KeyLookupService(
            StringRedisTemplate redisTemplate,
            @Qualifier("authResolverJdbcTemplate") JdbcTemplate authResolverJdbcTemplate,
            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.authResolverJdbcTemplate = authResolverJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns empty if the key is not found. Does not distinguish "not found" from "found but tenant inactive:
     * TenantContext carries everything the caller needs to make that judgment itself if a future batch requires it.
     */
    public Optional<TenantContext> resolve(String keyId) {
        Optional<TenantContext> cached = readFromCache(keyId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ApiKeyLookupRow> row =  readFromDatabase(keyId);
        row.ifPresent(r -> writeToCache(keyId, r));
        return row.map(KeyLookupService::toTenantContext);
    }
    private Optional<TenantContext> readFromCache(String keyId){
        String cacheKey= CACHE_KEY_PREFIX + keyId;
        String json;
        try{
            json= redisTemplate.opsForValue().get(cacheKey);
        }catch(DataAccessException e){
            log.warn("Redis unavailable for key lookup on keyId={}, "
                    + "falling back to PostgreSQL", maskKeyId(keyId), e);
            return Optional.empty();
        }
        if(json== null){
            return Optional.empty();
        }
        try{
            CachedApiKey cached= objectMapper.readValue(json, CachedApiKey.class);
            return Optional.of(toTenantContext(cached));
        }catch(Exception e){
            //malformed cache entry- treat as a miss rather than failing the request. falls through to the database
            return Optional.empty();
        }
    }
    private static String maskKeyId(String keyId){
        if(keyId== null || keyId.length() < 14){
            return "[REDACTED]";
        }
        return keyId.substring(0, 8) + "..." + keyId.substring(keyId.length() - 6);
    }
    private Optional<ApiKeyLookupRow> readFromDatabase(String keyId){
        List<ApiKeyLookupRow> results= authResolverJdbcTemplate.query(
                "SELECT key_id, tenant_id, project_id, environment, capabilities "
                        + "FROM api_keys WHERE key_id = ?", ROW_MAPPER, keyId);
        return results.stream().findFirst();
    }
    private void writeToCache(String keyId,ApiKeyLookupRow row){
        try{
            CachedApiKey cached= new CachedApiKey(row.tenantId().toString(), row.projectId().toString(),
                    row.environment(), Arrays.stream(row.capabilities()).map(Capability::valueOf)
                    .collect(Collectors.toUnmodifiableSet()));
            String json= objectMapper.writeValueAsString(cached);
            redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + keyId, json, CACHE_TTL);
        }catch(Exception e){
            //cache write failure  must never fail the request- the lookup already succeeded from the db.
            //next request simply misses cache again and re-reads postgres
        }
    }
    private static TenantContext toTenantContext(ApiKeyLookupRow row){
        return new TenantContext(new TenantId(row.tenantId().toString()), new ProjectId(row.projectId().toString()),
                row.keyId(), Environment.valueOf(row.environment()), Arrays.stream(row.capabilities()).map(
                        Capability::valueOf).collect(Collectors.toUnmodifiableSet()),
                //configversion is a static placeholder
                1);
    }
    private static TenantContext toTenantContext(CachedApiKey cached){
        return new TenantContext(new TenantId(cached.tenantId()), new ProjectId(cached.projectId()), null,
                Environment.valueOf(cached.environment()), cached.capabilities(), 1);
    }
    private record ApiKeyLookupRow(String keyId, UUID tenantId, UUID projectId, String environment, String[] capabilities){}
    private record CachedApiKey(String tenantId, String projectId, String environment, Set<Capability> capabilities){}
}