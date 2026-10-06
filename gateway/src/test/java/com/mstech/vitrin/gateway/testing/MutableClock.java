package com.mstech.vitrin.gateway.testing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class MutableClock extends Clock {
    private final AtomicReference<Instant> current;

    public MutableClock(Instant initial) {
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public void set(Instant instant) {
        current.set(Objects.requireNonNull(instant, "instant"));
    }

    public void advance(Duration duration) {
        current.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) {
            throw new IllegalArgumentException("Only UTC is supported");
        }
        return this;
    }

    @Override
    public Instant instant() {
        return current.get();
    }
}
