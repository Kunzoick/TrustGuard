package com.trustguard.shared.domain;

/**
 * API key lookup identifier. Per Rule 5.1, keyId is a random opaque
 * identifier — 16+ random bytes, base62-encoded, generated with
 * SecureRandom by the sdk module in a later batch (B-006). This record
 * only validates that a non-blank, reasonably-bounded string was
 * supplied; it does not re-validate the base62 alphabet or exact byte
 * length, since key generation and its precise encoding rules belong to
 * the sdk module, not shared.
 */
public record KeyId(String value) {

    private static final int MAX_LENGTH = 128;

    public KeyId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("KeyId value must not be null or blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "KeyId value must not exceed " + MAX_LENGTH + " characters, was: " + value.length());
        }
    }
}