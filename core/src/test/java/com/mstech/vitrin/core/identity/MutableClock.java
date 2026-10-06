package com.mstech.vitrin.core.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    MutableClock(Instant initial) {
        current = new AtomicReference<>(initial);
    }

    void set(Instant instant) {
        current.set(Objects.requireNonNull(instant));
    }

    void advance(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("This test clock is UTC only");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
