package com.trustguard.shared.enums;

/**
 * Actor identify model, oer Rule 6.3. Each value implies a completely different actorId construction role
 * USER(tenant's own internal user identifier-> opaque to trustGuard
 * ANONYMOUS(platform-computed HMAC-SHA256 digest of tenantId + sessionId
 * SERVICE(the calling service's own identifier string
 */
public enum ActorType {
    USER,
    ANONYMOUS,
    SERVICE
}
