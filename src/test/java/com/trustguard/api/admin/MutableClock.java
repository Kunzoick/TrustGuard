package com.trustguard.api.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Deterministic test clock: a fixed base captured once and truncated to whole seconds, plus an adjustable
 * offset. No wall-clock ticking, so boundary tests (window, 8-hour age) are exact.
 */
public class MutableClock extends Clock {

    private final Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private volatile Duration offset = Duration.ZERO;

    /**
     * Moves the clock forward.
     *
     * @param duration amount to add
     */
    public void advance(Duration duration) {
        offset = offset.plus(duration);
    }

    /** Resets the offset to zero. */
    public void reset() {
        offset = Duration.ZERO;
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
        return base.plus(offset);
    }
}