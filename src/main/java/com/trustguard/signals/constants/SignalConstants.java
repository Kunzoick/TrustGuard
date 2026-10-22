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

    //Cap for ordinary text fields, the VARCHAR(255) columns
    public static final int MAX_TEXT_LENGTH = 255;

    //Cap for the ip address, the VARCHAR(45) column
    public static final int MAX_IP_ADDRESS_LENGTH = 45;

    //Cap for the user agent.
    public static final int MAX_USER_AGENT_LENGTH = 512;

    //Largest canonical payload in UTF-8 bytes
    public static final int MAX_PAYLOAD_BYTES = 32768;

    //Regex for a well-formed UUID, 8-4-4-4-12 hex digits, either letter case.
    public static final String UUID_REGEX =

            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    /**
     * Regex that rejects the NUL character and unpaired surrogates (Ruling 66). Java regex
     * works on code points, so a valid surrogate pair counts as one code point and passes.
     */
    public static final String WELL_FORMED_TEXT_REGEX = "^[^\\u0000\\uD800-\\uDFFF]*$";

    // Violation template for a text field with NUL or an unpaired surrogate
    public static final String TEXT_NOT_WELL_FORMED_MESSAGE = "must not contain NUL or unpaired surrogate characters";

    // Violation template for a missing actorId on a USER or SERVICE event
    public static final String ACTOR_ID_REQUIRED_MESSAGE = "actorId is required for USER and SERVICE actors";

    //Violation template for an actorId sent with an ANONYMOUS event
    public static final String ACTOR_ID_FORBIDDEN_MESSAGE = "actorId must be absent for ANONYMOUS actors";

    /**
     * Violation template that marks an oversize payload. B-010 maps this to a violation.
     * with exactly this template to 400 PAYLOAD_TOO_LARGE
     */
    public static final String PAYLOAD_TOO_LARGE_MESSAGE = "payload exceeds the maximum allowed size";

    //Violation template for a payload value that cannot be serialized
    public static final String PAYLOAD_UNSUPPORTED_MESSAGE = "payload contains an unsupported value";

    // Violation template for payload text with NUL or an unpaired surrogate
    public static final String PAYLOAD_TEXT_MESSAGE =
            "payload text must not contain NUL or unpaired surrogate characters";


    private SignalConstants(){}
}
