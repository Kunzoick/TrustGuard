package com.trustguard.signals.util;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.trustguard.signals.constants.SignalConstants;

/**
 * HMAC-SHA256, SHA-256, strict UTF-8 and subkey-derivation helpers shared by
 * the signals generators (Rules 6.3 and 6.4, Rulings 50, 51 and 63). All hex
 * output is lowercase.
 *
 * <p>Contract: callers pass non-empty keys. A JRE without HmacSHA256 or
 * SHA-256 (not possible on a conforming JRE) surfaces as
 * {@link IllegalStateException}, which never crosses the module boundary.
 * Text that is not valid Unicode surfaces as {@link IllegalArgumentException}
 * from {@link #utf8(String)}; callers wrap it.
 */
final class HmacSha256 {

    private HmacSha256() {
    }

    /**
     * Wraps raw key bytes as an HMAC-SHA256 key. The bytes are copied.
     *
     * @param keyBytes the raw key, not empty
     * @return the key object
     */
    static SecretKey keyOf(byte[] keyBytes) {
        return new SecretKeySpec(keyBytes, SignalConstants.HMAC_ALGORITHM);
    }

    /**
     * Encodes text as UTF-8 strictly (Ruling 63). {@code String.getBytes} would
     * silently replace an unpaired surrogate with a question mark, so two
     * different inputs could hash to the same value. This method rejects them.
     *
     * @param text the text, not null
     * @return the UTF-8 bytes
     * @throws IllegalArgumentException if the text holds an unpaired surrogate;
     *                                  the message never contains the text
     */
    static byte[] utf8(String text) {
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("Text is not valid Unicode", ex);
        }
    }

    /**
     * Computes HMAC-SHA256.
     *
     * @param key  the HMAC key
     * @param data the message
     * @return the 32-byte MAC
     */
    static byte[] hmac(SecretKey key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(SignalConstants.HMAC_ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable or the key is unusable", ex);
        }
    }

    /**
     * Computes HMAC-SHA256 as 64 lowercase hex characters.
     *
     * @param key  the HMAC key
     * @param data the message
     * @return lowercase hex of the MAC
     */
    static String hmacHex(SecretKey key, byte[] data) {
        return toHex(hmac(key, data));
    }

    /**
     * Derives a purpose-specific subkey: HMAC-SHA256(master, label) (Ruling 50).
     *
     * @param masterKey the master key bytes, at least
     *                  {@link SignalConstants#MIN_MASTER_KEY_BYTES} long
     * @param label     the purpose label, encoded as UTF-8
     * @return the derived 32-byte key
     * @throws IllegalStateException if the master key is null or too short (Ruling 58,
     *                               startup-gate exemption, Ruling 2)
     */
    static SecretKey deriveSubkey(byte[] masterKey, String label) {
        if (masterKey == null || masterKey.length < SignalConstants.MIN_MASTER_KEY_BYTES) {
            throw new IllegalStateException("EVENT_HMAC_KEY must be at least "
                    + SignalConstants.MIN_MASTER_KEY_BYTES + " bytes");
        }
        return keyOf(hmac(keyOf(masterKey), utf8(label)));
    }

    /**
     * Computes SHA-256 as 64 lowercase hex characters.
     *
     * @param data the message
     * @return lowercase hex of the digest
     */
    static String sha256Hex(byte[] data) {
        try {
            return toHex(MessageDigest.getInstance(SignalConstants.SHA_256_ALGORITHM).digest(data));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /**
     * Renders bytes as lowercase hex.
     *
     * @param bytes the bytes
     * @return lowercase hex, two characters per byte
     */
    static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}