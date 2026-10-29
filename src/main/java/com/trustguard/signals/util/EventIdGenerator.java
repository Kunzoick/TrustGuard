package com.trustguard.signals.util;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.trustguard.shared.domain.EventId;

/**
 * Generates event ids as UUID v7 (Rule 6.1, RFC 9562): 48 bits of Unix time in milliseconds from
 * the injected {@link Clock}, the version nibble 7, the variant bits 10, and 74 random bits from
 * the injected {@link SecureRandom}.
 *
 * <p>Ids generated in different milliseconds sort in time order. Ids generated within the same
 * millisecond are unique with overwhelming probability but are NOT ordered among themselves.
 *
 * <p>Thread-safe: the clock and the random source are thread-safe and nothing else is shared.
 * Contract: a clock reading outside 0 to 2^48-1 milliseconds is a configuration fault and makes
 * {@link #generate()} throw {@link IllegalStateException}; the message holds no data.
 */
public final class EventIdGenerator {
    private static final int UUID_BYTES = 16;
    private static final int TIMESTAMP_BITS = 48;
    private static final int TIMESTAMP_BYTES = 6;
    private static final int BITS_PER_BYTE = 8;
    private static final long MAX_TIMESTAMP_MILLIS = (1L << TIMESTAMP_BITS) - 1;
    private static final int VERSION_INDEX = 6;
    private static final int RAND_A_LOW_INDEX = 7;
    private static final int VARIANT_INDEX = 8;
    private static final int RAND_B_REST_INDEX = 9;
    private static final int RANDOM_BYTES = 10;
    private static final int RANDOM_VERSION_SOURCE = 0;
    private static final int RANDOM_A_LOW_SOURCE = 1;
    private static final int RANDOM_VARIANT_SOURCE = 2;
    private static final int RANDOM_B_REST_SOURCE = 3;
    private static final int VERSION_7_BITS = 0x70;
    private static final int LOW_NIBBLE_MASK = 0x0F;
    private static final int VARIANT_BITS = 0x80;
    private static final int LOW_SIX_BITS_MASK = 0x3F;

    private final Clock clock;
    private final SecureRandom random;
    /**
     * Creates the generator.
     *
     * @param clock  the time source, not null
     * @param random the source of the random bits, not null
     */
    public EventIdGenerator(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }
    /**
     * Generates a new event id.
     *
     * @return a UUID v7 event id in lowercase hyphenated form
     * @throws IllegalStateException if the clock is outside the UUID v7 timestamp range
     */
    public EventId generate(){
        long millis = clock.millis();
        if (millis < 0 || millis > MAX_TIMESTAMP_MILLIS) {
            throw new IllegalStateException("Clock time is outside the UUID v7 timestamp range");
        }
        byte[] uuid= new byte[UUID_BYTES];
        for(int i= 0; i< TIMESTAMP_BYTES; i++){
            uuid[i]= (byte) (millis >>> (BITS_PER_BYTE * (TIMESTAMP_BYTES - 1 - i)));
        }
        byte[] bits= new byte[RANDOM_BYTES];
        random.nextBytes(bits);
        uuid[VERSION_INDEX] = (byte) (VERSION_7_BITS | (bits[RANDOM_VERSION_SOURCE] & LOW_NIBBLE_MASK));
        uuid[RAND_A_LOW_INDEX] = bits[RANDOM_A_LOW_SOURCE];
        uuid[VARIANT_INDEX] = (byte) (VARIANT_BITS | (bits[RANDOM_VARIANT_SOURCE] & LOW_SIX_BITS_MASK));
        System.arraycopy(bits, RANDOM_B_REST_SOURCE, uuid, RAND_B_REST_INDEX, RANDOM_BYTES - RANDOM_B_REST_SOURCE);
        ByteBuffer buffer= ByteBuffer.wrap(uuid);
        return new EventId(new UUID(buffer.getLong(), buffer.getLong()).toString());
    }
}
