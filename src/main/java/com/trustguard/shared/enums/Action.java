package com.trustguard.shared.enums;
/**
 * Enforcement recommendation (Rule 1.3 Layer 3, Rule 7.2). This is the
 * scoring engine's authoritative output — the tenant's own system
 * executes it. TrustGuard never touches enforcement execution (Rule 1.2).
 */
public enum Action {
    ALLOW,
    BLOCK,
    CHALLENGE
}
