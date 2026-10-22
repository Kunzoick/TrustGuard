package com.trustguard.signals.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.signals.constants.SignalConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSha256Test {

    /** RFC 4231 section 4.2, test case 1: HMAC-SHA-256, key 20 x 0x0b, data "Hi There". */
    private static final String RFC_4231_CASE_1 =
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7";

    /** NIST FIPS 180-2 example: SHA-256("abc"). */
    private static final String SHA_256_ABC =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private static final int RFC_KEY_BYTES = 20;
    private static final byte RFC_KEY_BYTE = 0x0b;

    private static byte[] sequentialKey(int length) {
        byte[] key = new byte[length];
        for (int i = 0; i < length; i++) {
            key[i] = (byte) i;
        }
        return key;
    }

    @Test
    @DisplayName("HMAC-SHA256 matches RFC 4231 test case 1")
    void hmacMatchesRfc4231CaseOne() {
        byte[] key = new byte[RFC_KEY_BYTES];
        Arrays.fill(key, RFC_KEY_BYTE);

        String mac = HmacSha256.hmacHex(HmacSha256.keyOf(key), "Hi There".getBytes(StandardCharsets.UTF_8));

        assertThat(mac).isEqualTo(RFC_4231_CASE_1);
    }

    @Test
    @DisplayName("SHA-256 of \"abc\" matches the NIST example")
    void sha256MatchesNistExample() {
        assertThat(HmacSha256.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(SHA_256_ABC);
    }

    @Test
    @DisplayName("hex output is lowercase with two characters per byte")
    void hexIsLowercase() {
        assertThat(HmacSha256.toHex(new byte[] {(byte) 0xAB, 0x0F, (byte) 0xFF})).isEqualTo("ab0fff");
    }

    @Test
    @DisplayName("strict UTF-8 encodes ASCII, non-ASCII and valid surrogate pairs like getBytes")
    void utf8EncodesValidText() {
        String emoji = new String(Character.toChars(0x1F600));

        assertThat(HmacSha256.utf8("abc")).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(HmacSha256.utf8("\u00e9")).containsExactly((byte) 0xC3, (byte) 0xA9);
        assertThat(HmacSha256.utf8(emoji)).containsExactly((byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80);
        assertThat(HmacSha256.utf8("")).isEmpty();
    }

    @Test
    @DisplayName("strict UTF-8 rejects a lone high surrogate and does not echo the text")
    void utf8RejectsLoneHighSurrogate() {
        String bad = "secret" + (char) 0xD800;

        assertThatThrownBy(() -> HmacSha256.utf8(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret");
    }

    @Test
    @DisplayName("strict UTF-8 rejects a lone low surrogate and a reversed pair")
    void utf8RejectsLoneLowSurrogate() {
        String lowOnly = String.valueOf((char) 0xDC00);
        String reversed = String.valueOf((char) 0xDC00) + (char) 0xD800;

        assertThatThrownBy(() -> HmacSha256.utf8(lowOnly)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HmacSha256.utf8(reversed)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("getBytes would have turned a lone surrogate into '?', strict UTF-8 does not")
    void utf8DoesNotReplaceWithQuestionMark() {
        String lone = String.valueOf((char) 0xD800);

        assertThat(lone.getBytes(StandardCharsets.UTF_8)).containsExactly((byte) '?');
        assertThatThrownBy(() -> HmacSha256.utf8(lone)).isInstanceOf(IllegalArgumentException.class);
        assertThat(HmacSha256.utf8("?")).containsExactly((byte) '?');
    }

    @Test
    @DisplayName("a derived subkey equals HMAC-SHA256(master, label) computed with javax.crypto")
    void subkeyMatchesReferenceComputation() throws GeneralSecurityException {
        byte[] master = sequentialKey(SignalConstants.MIN_MASTER_KEY_BYTES);
        Mac reference = Mac.getInstance("HmacSHA256");
        reference.init(new SecretKeySpec(master, "HmacSHA256"));
        byte[] expected = reference.doFinal(
                SignalConstants.ANON_SUBKEY_LABEL.getBytes(StandardCharsets.UTF_8));

        SecretKey derived = HmacSha256.deriveSubkey(master, SignalConstants.ANON_SUBKEY_LABEL);

        assertThat(derived.getEncoded()).isEqualTo(expected);
    }

    @Test
    @DisplayName("the two purpose labels derive different subkeys")
    void labelsDeriveDifferentSubkeys() {
        byte[] master = sequentialKey(SignalConstants.MIN_MASTER_KEY_BYTES);

        SecretKey anon = HmacSha256.deriveSubkey(master, SignalConstants.ANON_SUBKEY_LABEL);
        SecretKey idempotency = HmacSha256.deriveSubkey(master, SignalConstants.IDEMPOTENCY_SUBKEY_LABEL);

        assertThat(anon.getEncoded()).isNotEqualTo(idempotency.getEncoded());
    }

    @Test
    @DisplayName("a null master key is rejected")
    void nullMasterKeyIsRejected() {
        assertThatThrownBy(() -> HmacSha256.deriveSubkey(null, SignalConstants.ANON_SUBKEY_LABEL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("a master key one byte short is rejected")
    void shortMasterKeyIsRejected() {
        byte[] shortKey = sequentialKey(SignalConstants.MIN_MASTER_KEY_BYTES - 1);

        assertThatThrownBy(() -> HmacSha256.deriveSubkey(shortKey, SignalConstants.ANON_SUBKEY_LABEL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a master key of exactly 32 bytes is accepted")
    void minimumMasterKeyIsAccepted() {
        byte[] key = sequentialKey(SignalConstants.MIN_MASTER_KEY_BYTES);

        assertThat(HmacSha256.deriveSubkey(key, SignalConstants.ANON_SUBKEY_LABEL)).isNotNull();
    }
}