package com.trustguard.shared.enums;

/**
 * Central error code registry(rule 21.7). every error the platform can return is named here first,
 * no code may be used elsewhere until it exists in this enum. Once published a code is never renamed or removed(rule 13.3, rule 21.2); only addition are permitted
 *
 * OUTBOX_PUBLISH_FAILED added requires outboxPublishException to exist, but this registry orginally had no dedicated code for it, forcing an INTERNAL_ERROR mapping that hid a specific, retryable infrastructure failure behind a generic catch-all
 * The description field is a local convenience for readability
 */
public enum ErrorCode {
    // ==================== Authentication and authorization ====================
    INVALID_API_KEY("API key is malformed or unrecognized"),
    HMAC_VERIFICATION_FAILED("API key HMAC signature verification failed"),
    KEY_REVOKED("API key has been revoked"),
    CAPABILITY_INSUFFICIENT("API key lacks the capability required for this endpoint"),
    ENVIRONMENT_MISMATCH("API key environment does not match the request environment"),
    BROWSER_ORIGIN_REJECTED("Request Origin header indicates a browser call; SDK is server-side only"),
    ADMIN_AUTHENTICATION_FAILED("Admin login credentials are invalid"),
    ADMIN_ACCOUNT_LOCKED("Admin account is locked due to repeated failed login attempts"),
    ADMIN_SESSION_EXPIRED("Admin JWT has expired or token_version no longer matches"),

    // ==================== Validation ====================
    VALIDATION_FAILED("One or more request fields failed validation"),
    SESSION_ID_REQUIRED("sessionId is required for this anonymous actor configuration"),
    PAYLOAD_TOO_LARGE("Request payload exceeds the maximum allowed size"),
    TIMESTAMP_TOO_FAR_FUTURE("occurredAt is further in the future than the tenant's configured tolerance"),
    TIMESTAMP_TOO_OLD("occurredAt is older than the platform's archival cutoff"),
    POLICY_TYPE_NOT_SUPPORTED_IN_V1("Requested policy_type is not supported in V1"),
    AUDIT_TIER_NOT_PERMITTED("Requested audit tier is not permitted for this tenant category"),

    // ==================== Business logic ====================
    ACTOR_NOT_FOUND("No actor state exists for the given tenant and actorId"),
    DECISION_NOT_FOUND("No audit decision exists for the given decisionId"),
    TENANT_CONFIG_NOT_FOUND("No tenant configuration exists for the given tenantId"),
    DUPLICATE_EVENT("Event with this idempotency key has already been processed"),
    COLD_ACTOR("Actor has no prior history; decision derived from tenant's new-actor policy"),

    // ==================== Platform and infrastructure ====================
    SCORING_TIMEOUT("Scoring computation exceeded the tenant's configured timeout"),
    REDIS_UNAVAILABLE("Redis is unreachable"),
    ACTOR_STATE_BUILD_FAILED("Actor state rebuild from PostgreSQL failed or exceeded timeout"),
    OUTBOX_PUBLISH_FAILED("Failed to publish outbox event to RabbitMQ after maximum retry attempts"),
    INTERNAL_ERROR("An unmapped internal error occurred"),
    RATE_LIMIT_EXCEEDED("Request rate exceeds the tenant's configured limit"),

    // ==================== SDK-specific (TypeScript SDK only) ====================
    SDK_INITIALIZATION_FAILED("TypeScript SDK failed to initialize"),
    SDK_NETWORK_ERROR("TypeScript SDK encountered a network-level failure"),
    SDK_RESPONSE_PARSE_ERROR("TypeScript SDK could not parse the API response");

    private final String description;
    ErrorCode(String description) {
        this.description = description;
    }
    public String description() {
        return description;
    }
}
