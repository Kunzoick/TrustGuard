package com.trustguard.shared.domain;

/**
 * Actor identifier->  always a non-null, non-blank string (Rule 6.3)
 * intentionally not validated as a uuid. For user actors this is the tenant's own internal user identifier
 * -> it is opaque to trustGuard- may be an email, a numeric Id, a uuid or anything else the tenant already uses.
 * For ANONYMOUS actors it is a platform computed 64-char hex HMAC digest. For SERVICE actors it is the service's own identifier string.
 * None of these three forms are guaranteed to be a valid UUIDs, which is why this record does not enforce UUID format.
 */
public record ActorId(String value) {
    private static final int MAX_LENGTH= 256;

    public ActorId{
        if(value== null || value.isBlank()){
            throw new IllegalArgumentException("ActorId value must not be null or blank");
        }
        if(value.length() > MAX_LENGTH){
            throw new IllegalArgumentException("ActorId value must not exceed "+ MAX_LENGTH +
                    " characters, was: "+ value.length());
        }
    }
}
