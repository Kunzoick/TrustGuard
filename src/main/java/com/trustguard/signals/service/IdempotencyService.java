package com.trustguard.signals.service;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.trustguard.shared.domain.EventId;
import com.trustguard.shared.domain.TenantId;
import com.trustguard.signals.constants.IdempotencyConstants;

/**
 * The Redis fast path of the idempotency check (Rule 6.4, Rulings 37, 112 and 118). Keys are
 * {tenantId}:idempotency:{key} (Rule 4.1 Layer 5) and hold the original event id, written with SET NX and the
 * 24 hour TTL after the transaction has committed.
 *
 * <p>It is not transactional and never touches PostgreSQL. A Redis failure never fails ingestion: a failed read is
 * a miss, a failed write is skipped, and PostgreSQL remains the backstop. Failure logs carry the tenant id and the
 * exception class name only, never the exception object (its text can carry host names) and never the key.
 * A stored value that is not an event id counts as a miss.
 */
@Service
public class IdempotencyService {
    private static final Logger LOG = LoggerFactory.getLogger(IdempotencyService.class);
    private static final String MESSAGE_READ_FAILED = "Idempotency cache read failed. tenantId={} cause={}";
    private static final String MESSAGE_WRITE_FAILED = "Idempotency cache write failed. tenantId={} cause={}";

    private final StringRedisTemplate redis;
    //creates the service
    public IdempotencyService(StringRedisTemplate redis){
        this.redis= Objects.requireNonNull(redis, "redis");
    }

    //The remaining lifetime of an idempotency row: receivedAt plus the key TTL, minus now.
    // This is the same instant the repository stores as expire_at.
    public static Duration remainingTtl(Instant receivedAt, Instant now){
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(now, "now");
        Instant expiresAt= receivedAt.truncatedTo(ChronoUnit.MICROS).plus(IdempotencyConstants.KEY_TTL);
        return Duration.between(now, expiresAt);
    }
    //looks up the event that claimed a key.
    public Optional<EventId> find(TenantId tenantId, String idempotencyKey){
        try{
            String stored= redis.opsForValue().get(redisKey(tenantId, idempotencyKey));
            return parse(stored);
        }catch (DataAccessException ex){
            LOG.warn(MESSAGE_READ_FAILED, tenantId.value(), ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }
    //remembers teh event that claimed a key, if no one has yet, call only after the transaction commit
    public void remember(TenantId tenantId, String idempotencyKey, EventId eventId, Duration ttl){
        Objects.requireNonNull(ttl, "ttl");
        if(ttl.isZero() || ttl.isNegative()){
            return;
        }
        try{
            redis.opsForValue().setIfAbsent(redisKey(tenantId, idempotencyKey), eventId.value(), ttl);
        }catch (DataAccessException ex){
            LOG.warn(MESSAGE_WRITE_FAILED, tenantId.value(), ex.getClass().getSimpleName());
        }
    }
    private static String redisKey(TenantId tenantId, String idempotencyKey){
        return tenantId.value() + IdempotencyConstants.REDIS_KEY_SEGMENT + idempotencyKey;
    }
    private static Optional<EventId> parse(String stored){
        if(stored== null){
            return Optional.empty();
        }
        try{
            return Optional.of(new EventId(stored));
        }catch (IllegalArgumentException ex){
            return Optional.empty();
        }
    }
}
