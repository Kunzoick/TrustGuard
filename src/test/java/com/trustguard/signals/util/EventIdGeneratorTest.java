package com.trustguard.signals.util;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.trustguard.shared.domain.EventId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventIdGeneratorTest {

    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final int UNIQUENESS_SAMPLE = 100_000;
    private static final int LAYOUT_SAMPLE = 10_000;
    private static final int RANDOM_BYTES = 10;
    private static final int TIMESTAMP_SHIFT = 16;
    private static final int UUID_VERSION_7 = 7;
    private static final int IETF_VARIANT = 2;
    private static final int VERSION_CHAR_INDEX = 14;
    private static final int VARIANT_CHAR_INDEX = 19;
    private static final String RANGE_MESSAGE = "Clock time is outside the UUID v7 timestamp range";

    /** RFC 9562 section A.6 example: unix_ts_ms 0x017F22E279B0 (1645557742000), rand_a 0xCC3, rand_b 0x18C4DC0C0C07398F. */
    private static final long RFC_MILLIS = 1_645_557_742_000L;
    private static final String RFC_UUID = "017f22e2-79b0-7cc3-98c4-dc0c0c07398f";
    private static final byte[] RFC_RANDOM = {0x0C, (byte) 0xC3, 0x18, (byte) 0xC4, (byte) 0xDC, 0x0C, 0x0C, 0x07,
            0x39, (byte) 0x8F};

    /** Returns the same bytes on every call, so a test controls every random bit. */
    private static final class FixedRandom extends SecureRandom {

        private static final long serialVersionUID = 1L;

        private final byte[] bytes;

        FixedRandom(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public void nextBytes(byte[] target) {
            System.arraycopy(bytes, 0, target, 0, target.length);
        }
    }

    /** A clock whose reading the test moves between calls. */
    private static final class SteppingClock extends Clock {

        private final AtomicLong millis;

        SteppingClock(long start) {
            this.millis = new AtomicLong(start);
        }

        void set(long value) {
            millis.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    private static Clock clockAt(long millis) {
        return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static byte[] filled(int value) {
        byte[] bytes = new byte[RANDOM_BYTES];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static EventIdGenerator generator(long millis, byte[] randomBytes) {
        return new EventIdGenerator(clockAt(millis), new FixedRandom(randomBytes));
    }

    @Test
    @DisplayName("the RFC 9562 A.6 example is reproduced from its timestamp and random bits")
    void rfcExampleIsReproduced() {
        assertThat(generator(RFC_MILLIS, RFC_RANDOM).generate().value()).isEqualTo(RFC_UUID);
    }

    @Test
    @DisplayName("all-zero and all-one random bits show where the version and variant bits sit")
    void extremeRandomBitsShowTheLayout() {
        assertThat(generator(0, filled(0x00)).generate().value()).isEqualTo("00000000-0000-7000-8000-000000000000");
        assertThat(generator(0, filled(0xFF)).generate().value()).isEqualTo("00000000-0000-7fff-bfff-ffffffffffff");
        assertThat(generator(MAX_TIMESTAMP, filled(0x00)).generate().value())
                .isEqualTo("ffffffff-ffff-7000-8000-000000000000");
    }

    @Test
    @DisplayName("timestamps from 0 to 2^48-1 are accepted and anything outside is a configuration fault")
    void timestampBoundaries() {
        assertThat(generator(0, filled(0x00)).generate()).isNotNull();
        assertThat(generator(MAX_TIMESTAMP, filled(0x00)).generate()).isNotNull();

        assertThatThrownBy(() -> generator(-1, filled(0x00)).generate())
                .isInstanceOf(IllegalStateException.class).hasMessage(RANGE_MESSAGE);
        assertThatThrownBy(() -> generator(MAX_TIMESTAMP + 1, filled(0x00)).generate())
                .isInstanceOf(IllegalStateException.class).hasMessage(RANGE_MESSAGE);
    }

    @Test
    @DisplayName("the clock is read on every call")
    void theClockIsReadOnEveryCall() {
        SteppingClock clock = new SteppingClock(1_000L);
        EventIdGenerator generator = new EventIdGenerator(clock, new FixedRandom(filled(0x00)));

        EventId first = generator.generate();
        clock.set(2_000L);
        EventId second = generator.generate();

        assertThat(UUID.fromString(first.value()).getMostSignificantBits() >>> TIMESTAMP_SHIFT).isEqualTo(1_000L);
        assertThat(UUID.fromString(second.value()).getMostSignificantBits() >>> TIMESTAMP_SHIFT).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("version 7, variant 10 and the timestamp hold for every generated id")
    void layoutHoldsForManyIds() {
        long millis = 1_700_000_000_123L;
        EventIdGenerator generator = new EventIdGenerator(clockAt(millis), new SecureRandom());

        for (int i = 0; i < LAYOUT_SAMPLE; i++) {
            String id = generator.generate().value();
            UUID uuid = UUID.fromString(id);

            assertThat(uuid.version()).isEqualTo(UUID_VERSION_7);
            assertThat(uuid.variant()).isEqualTo(IETF_VARIANT);
            assertThat(uuid.getMostSignificantBits() >>> TIMESTAMP_SHIFT).isEqualTo(millis);
            assertThat(id.charAt(VERSION_CHAR_INDEX)).isEqualTo('7');
            assertThat("89ab").contains(String.valueOf(id.charAt(VARIANT_CHAR_INDEX)));
        }
    }

    @Test
    @DisplayName("100000 ids from one millisecond are all different")
    void idsAreUniqueWithinOneMillisecond() {
        EventIdGenerator generator = new EventIdGenerator(clockAt(1_700_000_000_123L), new SecureRandom());
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < UNIQUENESS_SAMPLE; i++) {
            ids.add(generator.generate().value());
        }

        assertThat(ids).hasSize(UNIQUENESS_SAMPLE);
    }

    @Test
    @DisplayName("ids from later milliseconds sort after ids from earlier ones, whatever the random bits")
    void idsAreOrderedByMillisecond() {
        List<Long> instants = List.of(0L, 1L, 255L, 256L, 65_535L, 65_536L, RFC_MILLIS, RFC_MILLIS + 1,
                MAX_TIMESTAMP);
        SecureRandom random = new SecureRandom();
        String previous = null;

        for (long millis : instants) {
            String current = new EventIdGenerator(clockAt(millis), random).generate().value();

            if (previous != null) {
                assertThat(current).isGreaterThan(previous);
            }
            previous = current;
        }
    }

    @Test
    @DisplayName("the id is a lowercase hyphenated EventId that survives its own normalisation")
    void theIdIsACanonicalEventId() {
        String id = generator(RFC_MILLIS, RFC_RANDOM).generate().value();

        assertThat(id).hasSize(36).isEqualTo(id.toLowerCase());
        assertThat(new EventId(id).value()).isEqualTo(id);
    }

    @Test
    @DisplayName("a null clock or random source is a programming error")
    void nullDependenciesAreRejected() {
        assertThatThrownBy(() -> new EventIdGenerator(null, new SecureRandom()))
                .isInstanceOf(NullPointerException.class).hasMessage("clock");
        assertThatThrownBy(() -> new EventIdGenerator(clockAt(0), null))
                .isInstanceOf(NullPointerException.class).hasMessage("random");
    }
}