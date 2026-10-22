package com.trustguard.signals.constants;
/**
 * Named constants for the signals module's identity and canonical-JSON
 * engine (Rule 17.4). DTO validation caps are added in B-009b.
 */
public class SignalConstants {
    //Prefix of every platform computed anonymous actor
    public static final String ANON_ACTOR_ID_PREFIX = "anon_";

    //HKDF like label for the anonymous actor id subkey
    public static final String ANON_SUBKEY_LABEL = "trustguard.anon-actor-id.v1";

    //label for the fallback idempotency-key subkey
    public static final String IDEMPOTENCY_SUBKEY_LABEL = "trustguard.idempotency-key.v1";

    //Minimum decoded length of Event-Hmac-key in bytes
    public static final int MIN_MASTER_KEY_BYTES = 32;

    //largest absolute scale of a number after trailing 0s are stripped
    public static final int MAX_NUMBER_SCALE = 400;

    //Deepest container nesting the canonical serializer accepts
    public static final int MAX_NESTING_DEPTH = 32;

    //size in bytes of the big endian length prefix of each hashed field
    public static final int LENGTH_PREFIX_BYTES = 4;

    //longest canonical output in chars
    public static final int MAX_CANONICAL_CHARS = 32768;

    //JCA name of the MAC algorithm
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    //JCA name of the digest algorithm
    public static final String SHA_256_ALGORITHM = "SHA-256";

    private SignalConstants(){}
}
