package com.trustguard.shared.enums;

/**
 * API key capability scope(rule 5.4) a key without the required capability for an endpoint
 * is rejected in middleware-> before it ever reaches a service method-> with error code CAPABILITY_INSUFFICIENT.
 */
public enum Capability {
    //can call trackLogin(), trackEvent()
    TRACK,
    //can call checkRisk(), rateLimit()
    CHECK,
    //can call confirmFraud(),markTrusted(),resolveFlasePositive()
    FEEDBACK,
    //tenant-scoped admin operations, Never embedded in SDK code
    ADMIN
}
