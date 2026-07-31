package com.epam.aidial.evaluation.runner.job;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic test {@link Clock} that returns a pre-scripted sequence of epoch-millis values, one
 * per {@link #millis()} call. {@link SseEventParser} reads the clock once at parse entry and once
 * per line, so scripting the sequence lets a test place precise gaps between successive line reads
 * to cross (or stay under) the idle and max-total deadlines without real waiting. Once the script is
 * exhausted the final value repeats.
 */
final class AdvancingClock extends Clock {

    private final long[] scriptedMillis;
    private final AtomicInteger cursor = new AtomicInteger(0);

    AdvancingClock(long... scriptedMillis) {
        this.scriptedMillis = scriptedMillis;
    }

    @Override
    public long millis() {
        int i = cursor.getAndIncrement();
        return scriptedMillis[Math.min(i, scriptedMillis.length - 1)];
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
