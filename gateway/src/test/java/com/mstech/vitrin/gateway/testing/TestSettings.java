package com.mstech.vitrin.gateway.testing;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.mstech.vitrin.gateway.ratelimit.RateLimitProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

public final class TestSettings {
    private TestSettings() {}

    public static GatewayProperties gateway(
            URI coreBaseUrl,
            URI coreHealthUrl,
            URI searchHealthUrl,
            Duration statusTimeout,
            Duration statusCacheTtl) {
        return new GatewayProperties(
                coreBaseUrl,
                coreHealthUrl,
                searchHealthUrl,
                "CF-Connecting-IP",
                "vitrin-core",
                "vitrin-api",
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                statusCacheTtl,
                statusTimeout);
    }

    public static RateLimitProperties rates(Map<String, Integer> overrides) {
        return new RateLimitProperties(
                null, overrides, Duration.ofMillis(250), Duration.ofSeconds(5), 50_000);
    }
}
