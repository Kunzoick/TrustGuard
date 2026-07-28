package com.trustguard.shared.enums;

/**
 * Audit record detail tier(rule 11.2). AGGREGATE is rejected outright for regulated tenant categories
 * -> (fintech, healthcare, government)- per rue 10.5 with error code AUDIT_TIER_NOT_PERMITTED
 */

public enum AuditTier {
    //complete snapshot: full cong=fig, all signals, full outcome. Regulated-tenant default.
    FULL,
    //decisionId, actorId, action, score, confidence, reasons, timestamp only
    STANDARD,
    //daily counts only. No per-decision records. Never available to regulated tenant
    AGGREGATE
}
