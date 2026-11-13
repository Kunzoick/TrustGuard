package com.trustguard.signals.constants;
import java.time.Duration;

/**
 * Constants of the idempotency check (Rule 6.4, Rulings 37 and 112). One TTL serves both stores: the Redis key
 * lives this long, and the PostgreSQL row's expires_at is receivedAt plus this. A tenant-configurable TTL
 * (Rule 6.4 allows up to 72 hours for FULL audit tier) waits for tenant configuration in B-015.
 */
public final class IdempotencyConstants {
    //How long an idempotency key suppresses a repeated event
    public static final Duration KEY_TTL= Duration.ofHours(24);
    //The segments between the tenant id and the client key in a redis key
    public static final String REDIS_KEY_SEGMENT= ":idempotency:";

    private IdempotencyConstants(){}
}
