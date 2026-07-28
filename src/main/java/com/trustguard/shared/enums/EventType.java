package com.trustguard.shared.enums;

/**
 * Event category for the universal event envelope(rule 6.1). Limited to the values explicitly maned in rule 6.1 and the concrete dtos
 * named in the roadmap
 */
public enum EventType {
    LOGIN,
    TRANSACTION,
    CONTENT_SUBMIT,
    RATE_CHECK
}
