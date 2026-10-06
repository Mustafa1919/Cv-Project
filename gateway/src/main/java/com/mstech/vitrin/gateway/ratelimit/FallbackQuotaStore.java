package com.mstech.vitrin.gateway.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FallbackQuotaStore implements QuotaStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(FallbackQuotaStore.class);

    private final QuotaStore primary;
    private final QuotaStore fallback;
    private final Clock clock;
    private final Duration retryInterval;

    private volatile @Nullable Instant retryAt;

    public FallbackQuotaStore(
            QuotaStore primary, QuotaStore fallback, Clock clock, Duration retryInterval) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryInterval = Objects.requireNonNull(retryInterval, "retryInterval");
        if (retryInterval.isNegative() || retryInterval.isZero()) {
            throw new IllegalArgumentException("Invalid Redis retry interval");
        }
    }

    @Override
    public QuotaDecision tryConsume(String key, int limitPerMinute) {
        Instant blockedUntil = retryAt;
        if (blockedUntil != null && clock.instant().isBefore(blockedUntil)) {
            return fallback.tryConsume(key, limitPerMinute);
        }

        // No lock: a lock here would queue every request behind one Redis round trip. Racing
        // threads may each try the primary once after an outage, which is harmless.
        try {
            QuotaDecision decision = primary.tryConsume(key, limitPerMinute);
            retryAt = null;
            return decision;
        } catch (RuntimeException exception) {
            retryAt = clock.instant().plus(retryInterval);
            LOGGER.warn(
                    "Redis quota store failed, using memory: {}", exception.getClass().getName());
        }
        return fallback.tryConsume(key, limitPerMinute);
    }

    public boolean degraded() {
        return retryAt != null;
    }
}
