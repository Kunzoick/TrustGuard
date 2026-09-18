package com.trustguard.sdk;

import com.trustguard.sdk.service.KeyHashVerificationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC4 — mean timing difference between valid and invalid key
 * verification must be under 1ms. Uses real HmacSHA256 computation,
 * not a mock, per the brief.
 *
 * CF-012 — measurement order is randomized (interleaved, shuffled
 * per-iteration assignment) rather than fixed alternating valid/
 * invalid, to avoid ordering effects correlating with one
 * measurement class (e.g. CPU frequency scaling ramping up over
 * the course of the run in a way that systematically favors
 * whichever case runs first each iteration).
 *
 * Both fixtures use identical-length keyIds (32 hex chars, matching
 * Rule 5.1's real key format) so a length-driven timing difference
 * in Mac.doFinal() cannot masquerade as a signature-comparison
 * timing leak.
 */
class TimingAttackTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int MEASURED_ITERATIONS = 1000;
    private static final int DISCARD_SAMPLES = 100;
    private static final long MAX_MEAN_DIFFERENCE_NANOS = 1_000_000L; // 1ms

    private static final String SIGNING_KEY = "test-signing-key-for-timing-analysis";
    private static final String KEY_ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"; // 32 hex chars

    private final KeyHashVerificationService service = new KeyHashVerificationService(SIGNING_KEY);

    @Test
    void valid_and_invalid_key_verification_have_statistically_indistinguishable_timing() {
        String validHmac = computeRealHmac(KEY_ID);
        String invalidHmac = "f".repeat(64);

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            service.verify(KEY_ID, validHmac);
            service.verify(KEY_ID, invalidHmac);
        }

        // CF-012 — build a randomized sequence of MEASURED_ITERATIONS
        // "valid" and MEASURED_ITERATIONS "invalid" trials, shuffled
        // together, rather than a fixed alternating pattern.
        List<Boolean> trialOrder = new ArrayList<>(MEASURED_ITERATIONS * 2);
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            trialOrder.add(Boolean.TRUE);
            trialOrder.add(Boolean.FALSE);
        }
        Collections.shuffle(trialOrder, new Random(42)); // fixed seed for reproducibility

        long[] validTimings = new long[MEASURED_ITERATIONS];
        long[] invalidTimings = new long[MEASURED_ITERATIONS];
        int validIndex = 0;
        int invalidIndex = 0;

        for (boolean isValidTrial : trialOrder) {
            if (isValidTrial) {
                long start = System.nanoTime();
                service.verify(KEY_ID, validHmac);
                validTimings[validIndex++] = System.nanoTime() - start;
            } else {
                long start = System.nanoTime();
                service.verify(KEY_ID, invalidHmac);
                invalidTimings[invalidIndex++] = System.nanoTime() - start;
            }
        }

        double meanValid = mean(validTimings, DISCARD_SAMPLES);
        double meanInvalid = mean(invalidTimings, DISCARD_SAMPLES);

        assertThat(Math.abs(meanValid - meanInvalid)).isLessThan(MAX_MEAN_DIFFERENCE_NANOS);
    }

    private static double mean(long[] samples, int discardFirst) {
        long sum = 0;
        int count = 0;
        for (int i = discardFirst; i < samples.length; i++) {
            sum += samples[i];
            count++;
        }
        return (double) sum / count;
    }

    private static String computeRealHmac(String keyId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    SIGNING_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] bytes = mac.doFinal(keyId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}