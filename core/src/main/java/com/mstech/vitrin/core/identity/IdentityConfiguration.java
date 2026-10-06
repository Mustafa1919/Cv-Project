package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.TokenVerifier;
import io.micrometer.tracing.Tracer;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {
    // The guard reports every missing production setting by name; without this the key loader
    // can fail first and hide that message. The loader still refuses on its own.
    @Bean
    @DependsOn("startupGuard")
    SigningKeys signingKeys(IdentityProperties properties, Environment environment) {
        return new SigningKeys(properties, environment.acceptsProfiles(Profiles.of("prod")));
    }

    @Bean
    TokenVerifier tokenVerifier(
            IdentityProperties properties, Clock clock, SigningKeys signingKeys) {
        return new TokenVerifier(properties.issuer(), properties.audience(), clock, signingKeys);
    }

    @Bean
    TokenIssuer tokenIssuer(IdentityProperties properties, SigningKeys keys, Clock clock) {
        return new TokenIssuer(properties, keys, clock);
    }

    @Bean
    SessionService sessionService(
            IdentityProperties properties,
            TokenIssuer issuer,
            TokenVerifier verifier,
            Clock clock) {
        return new SessionService(properties, issuer, verifier, clock, new SecureRandom());
    }

    @Bean
    IdentityCookies identityCookies(Clock clock) {
        return new IdentityCookies(clock);
    }

    @Bean
    IdentityProblems identityProblems(JsonMapper mapper, ObjectProvider<Tracer> tracers) {
        return new IdentityProblems(mapper, tracers);
    }

    @Bean
    AccessTokenFilter accessTokenFilter(TokenVerifier verifier, IdentityProblems problems) {
        return new AccessTokenFilter(verifier, problems);
    }
}
