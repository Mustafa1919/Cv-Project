package com.mstech.vitrin.gateway.ratelimit;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitrin.ratelimit")
public record RateLimitProperties(
        @Nullable Path addressHashKeyFile,
        Map<String, Integer> perMinute,
        @DefaultValue("250ms") Duration redisTimeout,
        @DefaultValue("5s") Duration redisRetryInterval,
        @DefaultValue("50000") int fallbackMaxEntries) {

    public RateLimitProperties {
        Map<String, Integer> merged =
                new HashMap<>(
                        Map.of(
                                "public", 30,
                                "session-create", 10,
                                "session-refresh", 10,
                                "authenticated", 120,
                                "preflight", 60,
                                "session-read", 60));
        if (perMinute != null) {
            perMinute.forEach(
                    (group, limit) -> {
                        if (group == null || group.isBlank() || limit == null || limit < 1) {
                            throw new IllegalArgumentException(
                                    "Invalid vitrin.ratelimit.per-minute");
                        }
                        merged.put(group, limit);
                    });
        }
        perMinute = Map.copyOf(merged);
        requirePositive(redisTimeout, "redis-timeout");
        requirePositive(redisRetryInterval, "redis-retry-interval");
        if (fallbackMaxEntries < 1) {
            throw new IllegalArgumentException("Invalid vitrin.ratelimit.fallback-max-entries");
        }
    }

    public int limitFor(String group) {
        Integer limit = perMinute.get(group);
        if (limit == null) {
            throw new IllegalStateException("Missing vitrin.ratelimit.per-minute group");
        }
        return limit;
    }

    private static void requirePositive(@Nullable Duration duration, String property) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Invalid vitrin.ratelimit." + property);
        }
    }
}
