package com.trustguard.sdk.service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * it recomputes the HMAC from keyId and compares it constant-time against the provided hash
 * this is the component TimingAttackTest exercises directly
 */
@Service
public class KeyHashVerificationService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final String hmacSigningKey;

    public KeyHashVerificationService(@Value("${trustguard.security.hmac-signing-key}")
                                      String hmacSigningKey){
        this.hmacSigningKey = hmacSigningKey;
    }
    /**
     * it uses MessageDigest.isEqual for the comparison to avoid timing attacks
     * it never uses String.equas(). returns false on any internal computation error rather than throwing, so a single verification
     * cal site is the only branch point caller needs to handle
     */
    public boolean verify(String keyId, String providedHmac){
        String expectedHmac= computeExpectedHmac(keyId);
        return MessageDigest.isEqual(providedHmac.getBytes(StandardCharsets.UTF_8),
                expectedHmac.getBytes(StandardCharsets.UTF_8));
    }
    private String computeExpectedHmac(String keyId){
        try{
            Mac mac= Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec= new SecretKeySpec(hmacSigningKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] expectedBytes= mac.doFinal(keyId.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(expectedBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            //HmacSHA256 is a JDK-guaranteed algorithm and the key spec is always valid non-null bytes
            //so this branch is never reached in pratice. Fail loudly rather than returning sentinel that could be compared as if it were a real HMAC
            throw new IllegalStateException("HMAC computation failed unexpectedly", e);
        }
    }
}
