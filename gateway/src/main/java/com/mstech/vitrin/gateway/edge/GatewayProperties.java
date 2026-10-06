package com.mstech.vitrin.gateway.edge;

import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitrin.gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:8081") URI coreBaseUrl,
        @DefaultValue("http://localhost:9081/actuator/health/readiness") URI coreHealthUrl,
        @DefaultValue("http://localhost:9082/actuator/health/readiness") URI searchHealthUrl,
        @DefaultValue("CF-Connecting-IP") String clientAddressHeader,
        @DefaultValue("vitrin-core") String tokenIssuer,
        @DefaultValue("vitrin-api") String tokenAudience,
        @DefaultValue("10s") Duration jwksMinRefreshInterval,
        @DefaultValue("5m") Duration jwksMaxAge,
        @DefaultValue("1s") Duration jwksTimeout,
        @DefaultValue("5s") Duration statusCacheTtl,
        @DefaultValue("500ms") Duration statusTimeout) {

    public GatewayProperties {
        requireHttpUri(coreBaseUrl, "core-base-url");
        requireHttpUri(coreHealthUrl, "core-health-url");
        requireHttpUri(searchHealthUrl, "search-health-url");
        requireText(clientAddressHeader, "client-address-header");
        requireText(tokenIssuer, "token-issuer");
        requireText(tokenAudience, "token-audience");
        if (!clientAddressHeader.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw invalid("client-address-header");
        }
        requirePositive(jwksMinRefreshInterval, "jwks-min-refresh-interval");
        requirePositive(jwksMaxAge, "jwks-max-age");
        requirePositive(jwksTimeout, "jwks-timeout");
        requirePositive(statusCacheTtl, "status-cache-ttl");
        requirePositive(statusTimeout, "status-timeout");
    }

    private static void requireHttpUri(@Nullable URI uri, String property) {
        if (uri == null
                || !uri.isAbsolute()
                || uri.getHost() == null
                || (!"http".equalsIgnoreCase(uri.getScheme())
                        && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() < -1
                || uri.getPort() > 65535) {
            throw invalid(property);
        }
    }

    private static void requireText(@Nullable String value, String property) {
        if (value == null || value.isBlank()) {
            throw invalid(property);
        }
    }

    private static void requirePositive(@Nullable Duration duration, String property) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw invalid(property);
        }
    }

    private static IllegalArgumentException invalid(String property) {
        return new IllegalArgumentException("Invalid vitrin.gateway." + property);
    }
}
