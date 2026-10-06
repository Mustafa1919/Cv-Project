package com.mstech.vitrin.core.identity;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("vitrin.identity")
public record IdentityProperties(
        @Nullable Path signingKeyFile,
        @DefaultValue List<Path> retiredPublicKeyFiles,
        @DefaultValue("vitrin-core") String issuer,
        @DefaultValue("vitrin-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("24h") Duration guestSessionTtl) {
    public IdentityProperties {
        retiredPublicKeyFiles =
                retiredPublicKeyFiles == null ? List.of() : List.copyOf(retiredPublicKeyFiles);
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(audience);
        Objects.requireNonNull(accessTokenTtl);
        Objects.requireNonNull(guestSessionTtl);
        if (accessTokenTtl.isNegative()
                || accessTokenTtl.isZero()
                || guestSessionTtl.isNegative()
                || guestSessionTtl.isZero()
                || accessTokenTtl.compareTo(guestSessionTtl) > 0) {
            throw new IllegalArgumentException("Invalid vitrin.identity token lifetimes");
        }
    }
}
