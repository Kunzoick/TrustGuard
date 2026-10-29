package com.trustguard.signals.config;
import java.util.Arrays;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.trustguard.signals.constants.SignalConstants;
import com.trustguard.signals.util.AnonymousActorIdGenerator;
import com.trustguard.signals.util.IdempotencyKeyGenerator;

/**
 * Startup gate and wiring for EVENT_HMAC_KEY (Rules 5.9 and 6.3, Rulings 2, 50, 58 and 82).
 * Exposes the anonymous-actor-id generator and the idempotency-key generator, both built from the
 * same decoded key. It does not expose an EventEnvelopeMapper.
 *
 * <p>The gate runs inside the bean methods, never in a constructor. The application refuses to start
 * when the key is missing, blank, not standard-alphabet Base64, or decodes to fewer than
 * {@link SignalConstants#MIN_MASTER_KEY_BYTES} bytes. The messages are fixed text and never contain
 * any part of the value. The decoded array is wiped once a generator has copied it.
 *
 * <p>Rotating the key changes every anonymous actor id (Ruling 50).
 */
@Configuration(proxyBeanMethods = false)
public class EventHmacConfig {
    private static final String HMAC_KEY_PLACEHOLDER= "${trustguard.events.hmac-key:}";
    private static final String MESSAGE_MISSING = "EVENT_HMAC_KEY is missing or blank.";
    private static final String MESSAGE_NOT_BASE64 = "EVENT_HMAC_KEY is not valid Base64.";
    private static final String MESSAGE_TOO_SHORT =
            "EVENT_HMAC_KEY must decode to at least " + SignalConstants.MIN_MASTER_KEY_BYTES + " bytes.";
    /**
     * Builds the anonymous actor id generator.
     *
     * @param base64Key the EVENT_HMAC_KEY value; never logged
     * @return the generator
     * @throws IllegalStateException if the key is missing, blank, not Base64 or too short
     */
    @Bean
    public AnonymousActorIdGenerator anonymousActorIdGenerator(@Value(HMAC_KEY_PLACEHOLDER) String base64Key){
        byte[] key= decodeKey(base64Key);
        try{
            return new AnonymousActorIdGenerator(key);
        }finally {
            Arrays.fill(key, (byte) 0);
        }
    }
    /**
     * Builds the fallback idempotency key generator.
     *
     * @param base64Key the EVENT_HMAC_KEY value; never logged
     * @return the generator
     * @throws IllegalStateException if the key is missing, blank, not Base64 or too short
     */
    @Bean
    public IdempotencyKeyGenerator idempotencyKeyGenerator(@Value(HMAC_KEY_PLACEHOLDER) String base64Key){
        byte[] key = decodeKey(base64Key);
        try {
            return new IdempotencyKeyGenerator(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }
    private static byte[] decodeKey(String base64Key){
        if(base64Key== null || base64Key.isBlank()){
            throw new IllegalStateException(MESSAGE_MISSING);
        }
        byte[] decoded;
        try{
            decoded= Base64.getDecoder().decode(base64Key);
        }catch(IllegalArgumentException ex){
            throw new IllegalStateException(MESSAGE_NOT_BASE64);
        }
        if(decoded.length < SignalConstants.MIN_MASTER_KEY_BYTES){
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException(MESSAGE_TOO_SHORT);
        }
        return decoded;
    }
}
