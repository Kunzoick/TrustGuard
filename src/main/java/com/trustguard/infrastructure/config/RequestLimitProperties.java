package com.trustguard.infrastructure.config;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request body limits for the tenant chain, in bytes (Rule 16.3). Bound from the
 * trustguard.request-limits prefix and validated at startup: a limit that is missing (so zero),
 * not positive, or Integer.MAX_VALUE (which would overflow the filter's limit-plus-one read) stops
 * the application. Messages name the property and never contain a value.
 *
 * @param signalsBytes limit for /api/v1/signals/** (trackLogin, trackEvent, trackAction)
 * @param defaultBytes limit for every other /api/v1/** path
 */
@ConfigurationProperties("trustguard.request-limits")
public record RequestLimitProperties(int signalsBytes, int defaultBytes) {
    private static final int MAX_LIMIT_BYTES= Integer.MAX_VALUE - 1;
    //validates both limits
    public RequestLimitProperties{
        requireValid(signalsBytes, "trustguard.request-limits.signals-bytes");
        requireValid(defaultBytes, "trustguard.request-limits.default-bytes");
    }
    private void requireValid(int limit, String property){
        if(limit < 1 || limit > MAX_LIMIT_BYTES){
            throw new IllegalArgumentException(property + " must be a positive number of bytes.");
        }
    }
}
