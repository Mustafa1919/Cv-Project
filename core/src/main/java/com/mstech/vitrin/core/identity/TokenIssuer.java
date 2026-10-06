package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

final class TokenIssuer {
    private final IdentityProperties properties;
    private final SigningKeys keys;
    private final Clock clock;

    TokenIssuer(IdentityProperties properties, SigningKeys keys, Clock clock) {
        this.properties = properties;
        this.keys = keys;
        this.clock = clock;
    }

    IssuedToken access(String sessionId, Role role, Instant sessionExpiresAt) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant normalExpiry = now.plus(properties.accessTokenTtl());
        Instant expiry =
                (normalExpiry.isBefore(sessionExpiresAt) ? normalExpiry : sessionExpiresAt)
                        .truncatedTo(ChronoUnit.SECONDS);
        return issue(TokenKind.ACCESS, sessionId, role, now, expiry);
    }

    IssuedToken refresh(String sessionId, Role role, Instant sessionExpiresAt) {
        return issue(
                TokenKind.REFRESH,
                sessionId,
                role,
                clock.instant().truncatedTo(ChronoUnit.SECONDS),
                sessionExpiresAt.truncatedTo(ChronoUnit.SECONDS));
    }

    private IssuedToken issue(
            TokenKind kind, String sessionId, Role role, Instant issuedAt, Instant expiresAt) {
        JWSObject object =
                new JWSObject(
                        new JWSHeader.Builder(JWSAlgorithm.ES256)
                                .type(new JOSEObjectType(kind.headerType()))
                                .keyID(keys.activeKeyId())
                                .build(),
                        new Payload(
                                Map.of(
                                        "iss", properties.issuer(),
                                        "aud", List.of(properties.audience()),
                                        "sid", sessionId,
                                        "role", role.claimValue(),
                                        "iat", issuedAt.getEpochSecond(),
                                        "exp", expiresAt.getEpochSecond())));
        try {
            keys.sign(object);
            return new IssuedToken(object.serialize(), expiresAt);
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to sign an identity token");
        }
    }
}
