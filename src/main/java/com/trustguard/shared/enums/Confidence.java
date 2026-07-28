package com.trustguard.shared.enums;
/**
 * Confidence level attached to a risk decision (Rule 9.2). NONE is
 * reserved exclusively for DEGRADED responses (Rule 9.1) — the platform
 * must never fabricate a HIGH/MEDIUM/LOW confidence value alongside a
 * score it did not actually compute.
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE
}
