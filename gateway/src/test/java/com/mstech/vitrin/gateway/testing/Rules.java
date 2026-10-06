package com.mstech.vitrin.gateway.testing;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.TokenRequirement;
import com.mstech.vitrin.gateway.route.Upstream;
import com.mstech.vitrin.platform.token.Role;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

public final class Rules {
    private Rules() {}

    public static RouteRule publicGet(String path) {
        return new RouteRule(
                "public-get",
                "GET",
                path,
                Upstream.LOCAL,
                TokenRequirement.NONE,
                Set.of(),
                "public",
                null,
                false,
                false);
    }

    public static RouteRule tokenGet(String path, Role... roles) {
        return new RouteRule(
                "token-get",
                "GET",
                path,
                Upstream.CORE,
                TokenRequirement.REQUIRED,
                Set.of(roles),
                "authenticated",
                "session-read",
                false,
                false);
    }

    public static RouteRule publicPost(String path) {
        return new RouteRule(
                "public-post",
                "POST",
                path,
                Upstream.CORE,
                TokenRequirement.NONE,
                Set.of(),
                "session-create",
                null,
                false,
                false);
    }

    public static GatewayProperties gatewayProperties(String clientAddressHeader) {
        return new GatewayProperties(
                URI.create("http://localhost:8081"),
                URI.create("http://localhost:9081/actuator/health/readiness"),
                URI.create("http://localhost:9082/actuator/health/readiness"),
                clientAddressHeader,
                "vitrin-core",
                "vitrin-api",
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofMillis(500));
    }
}
