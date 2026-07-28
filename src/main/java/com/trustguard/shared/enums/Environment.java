package com.trustguard.shared.enums;

/**
 * Deployment environment. Every API key and every event is scoped to exactly one of these three values
 * (rule 5.3)-> this is the mechanism tha t prevents a staging key from ever writing to production tables.
 */
public enum Environment {
    PRODUCTION,
    STAGING,
    DEVELOPMENT

}
