package com.mstech.vitrin.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.TokenRejectedException;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenIssuerTests {
    private static final Instant NOW = Instant.parse("2026-10-05T11:00:00Z");
    private static final String SESSION_ID = "0123456789abcdef0123456789abcdef";

    @Test
    void accessTokenHasExactHeaderClaimsAndLifetime(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        IssuedToken issued =
                fixture.issuer()
                        .access(SESSION_ID, Role.GUEST_COMPANY, NOW.plus(Duration.ofHours(24)));
        SignedJWT jwt = SignedJWT.parse(issued.value());

        assertHeader(jwt, TokenKind.ACCESS, fixture.keys());
        assertClaims(jwt, Role.GUEST_COMPANY, NOW, NOW.plus(Duration.ofMinutes(15)));
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(fixture.verifier().verify(issued.value(), TokenKind.ACCESS))
                .isEqualTo(
                        new VerifiedToken(
                                TokenKind.ACCESS,
                                SESSION_ID,
                                Role.GUEST_COMPANY,
                                NOW,
                                issued.expiresAt()));
    }

    @Test
    void refreshTokenExpiresExactlyWithSession(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        Instant sessionExpiry = NOW.plus(Duration.ofHours(24));
        IssuedToken issued =
                fixture.issuer().refresh(SESSION_ID, Role.GUEST_COMPANY, sessionExpiry);
        SignedJWT jwt = SignedJWT.parse(issued.value());

        assertHeader(jwt, TokenKind.REFRESH, fixture.keys());
        assertClaims(jwt, Role.GUEST_COMPANY, NOW, sessionExpiry);
        assertThat(issued.expiresAt()).isEqualTo(sessionExpiry);
        assertThat(fixture.verifier().verify(issued.value(), TokenKind.REFRESH))
                .isEqualTo(
                        new VerifiedToken(
                                TokenKind.REFRESH,
                                SESSION_ID,
                                Role.GUEST_COMPANY,
                                NOW,
                                sessionExpiry));
    }

    @Test
    void preservesDemoCandidateRoleWithoutAddingAnotherFlow(@TempDir Path directory)
            throws Exception {
        Fixture fixture = fixture(directory);
        IssuedToken issued =
                fixture.issuer().access(SESSION_ID, Role.DEMO_CANDIDATE, NOW.plusSeconds(1000));

        assertThat(fixture.verifier().verify(issued.value(), TokenKind.ACCESS).role())
                .isEqualTo(Role.DEMO_CANDIDATE);
        assertClaims(
                SignedJWT.parse(issued.value()), Role.DEMO_CANDIDATE, NOW, NOW.plusSeconds(900));
    }

    @Test
    void refreshedAccessExpiryIsCappedBySessionExpiry(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        Instant sessionExpiry = NOW.plus(Duration.ofHours(24));
        IssuedToken refresh =
                fixture.issuer().refresh(SESSION_ID, Role.GUEST_COMPANY, sessionExpiry);
        fixture.clock().set(sessionExpiry.minusSeconds(100));

        SessionService.RefreshedSession refreshed = fixture.sessions().refresh(refresh.value());

        assertThat(refreshed.identity().sessionId()).isEqualTo(SESSION_ID);
        assertThat(refreshed.identity().role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(refreshed.access().expiresAt()).isEqualTo(sessionExpiry);
        assertThat(refreshed.identity().accessExpiresAt()).isEqualTo(sessionExpiry);
        assertThat(
                        fixture.verifier()
                                .verify(refreshed.access().value(), TokenKind.ACCESS)
                                .expiresAt())
                .isEqualTo(sessionExpiry);
        assertThat(fixture.verifier().verify(refresh.value(), TokenKind.REFRESH).expiresAt())
                .isEqualTo(sessionExpiry);
    }

    @Test
    void numericDatesHaveWholeSecondPrecision(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        fixture.clock().set(NOW.plusNanos(123_456_789));
        Instant sessionExpiry = NOW.plusSeconds(1000).plusNanos(987_654_321);

        IssuedToken access = fixture.issuer().access(SESSION_ID, Role.GUEST_COMPANY, sessionExpiry);
        IssuedToken refresh =
                fixture.issuer().refresh(SESSION_ID, Role.GUEST_COMPANY, sessionExpiry);

        assertThat(access.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(refresh.expiresAt()).isEqualTo(NOW.plusSeconds(1000));
        assertThat(fixture.verifier().verify(access.value(), TokenKind.ACCESS).issuedAt())
                .isEqualTo(NOW);
        assertThat(fixture.verifier().verify(refresh.value(), TokenKind.REFRESH).issuedAt())
                .isEqualTo(NOW);
    }

    @Test
    void serviceCreatesGuestSessionWithBothCredentials(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);

        SessionService.NewSession created = fixture.sessions().create();
        VerifiedToken access =
                fixture.verifier().verify(created.access().value(), TokenKind.ACCESS);
        VerifiedToken refresh =
                fixture.verifier().verify(created.refresh().value(), TokenKind.REFRESH);

        assertThat(created.identity().sessionId()).matches("[0-9a-f]{32}");
        assertThat(created.identity().role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(access.sessionId()).isEqualTo(created.identity().sessionId());
        assertThat(refresh.sessionId()).isEqualTo(created.identity().sessionId());
        assertThat(access.role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(refresh.role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(access.expiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(refresh.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(created.identity().accessExpiresAt()).isEqualTo(access.expiresAt());
    }

    @Test
    void serviceRefreshPreservesIdentityAndDoesNotExtendSession(@TempDir Path directory)
            throws Exception {
        Fixture fixture = fixture(directory);
        SessionService.NewSession created = fixture.sessions().create();
        fixture.clock().advance(Duration.ofHours(1));

        SessionService.RefreshedSession refreshed =
                fixture.sessions().refresh(created.refresh().value());

        assertThat(refreshed.identity().sessionId()).isEqualTo(created.identity().sessionId());
        assertThat(refreshed.identity().role()).isEqualTo(created.identity().role());
        assertThat(refreshed.access().expiresAt()).isEqualTo(NOW.plusSeconds(4500));
        assertThat(
                        fixture.verifier()
                                .verify(created.refresh().value(), TokenKind.REFRESH)
                                .expiresAt())
                .isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void serviceRejectsRefreshAtSessionExpiration(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        SessionService.NewSession created = fixture.sessions().create();
        fixture.clock().set(created.refresh().expiresAt());

        assertThatThrownBy(() -> fixture.sessions().refresh(created.refresh().value()))
                .isInstanceOfSatisfying(
                        TokenRejectedException.class,
                        exception ->
                                assertThat(exception.reason())
                                        .isEqualTo(TokenRejectedException.Reason.EXPIRED));
    }

    @Test
    void serviceRejectsAccessTokenAsRefreshCredential(@TempDir Path directory) throws Exception {
        Fixture fixture = fixture(directory);
        SessionService.NewSession created = fixture.sessions().create();

        assertThatThrownBy(() -> fixture.sessions().refresh(created.access().value()))
                .isInstanceOfSatisfying(
                        TokenRejectedException.class,
                        exception ->
                                assertThat(exception.reason())
                                        .isEqualTo(TokenRejectedException.Reason.WRONG_TYPE));
    }

    private static void assertHeader(SignedJWT jwt, TokenKind kind, SigningKeys keys) {
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(Objects.requireNonNull(jwt.getHeader().getType()).toString())
                .isEqualTo(kind.headerType());
        assertThat(jwt.getHeader().getKeyID()).isEqualTo(keys.activeKeyId());
        assertThat(jwt.getHeader().toJSONObject()).containsOnlyKeys("alg", "typ", "kid");
    }

    private static void assertClaims(SignedJWT jwt, Role role, Instant issuedAt, Instant expiresAt)
            throws Exception {
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertThat(claims.getClaims()).containsOnlyKeys("iss", "aud", "sid", "role", "iat", "exp");
        assertThat(claims.getIssuer()).isEqualTo("vitrin-core");
        assertThat(claims.getAudience()).containsExactly("vitrin-api");
        assertThat(claims.getStringClaim("sid")).isEqualTo(SESSION_ID);
        assertThat(claims.getStringClaim("role")).isEqualTo(role.claimValue());
        assertThat(Objects.requireNonNull(claims.getIssueTime()).toInstant()).isEqualTo(issuedAt);
        assertThat(Objects.requireNonNull(claims.getExpirationTime()).toInstant())
                .isEqualTo(expiresAt);
        assertThat(claims.getSubject()).isNull();
        assertThat(claims.getJWTID()).isNull();
    }

    private static Fixture fixture(Path directory) throws Exception {
        IdentityProperties properties =
                new IdentityProperties(
                        TestKeyFiles.writeSigningKey(directory),
                        List.of(),
                        "vitrin-core",
                        "vitrin-api",
                        Duration.ofMinutes(15),
                        Duration.ofHours(24));
        MutableClock clock = new MutableClock(NOW);
        SigningKeys keys = new SigningKeys(properties, false);
        TokenVerifier verifier =
                new TokenVerifier(properties.issuer(), properties.audience(), clock, keys);
        TokenIssuer issuer = new TokenIssuer(properties, keys, clock);
        SessionService sessions =
                new SessionService(properties, issuer, verifier, clock, new SecureRandom());
        return new Fixture(clock, keys, issuer, verifier, sessions);
    }

    private record Fixture(
            MutableClock clock,
            SigningKeys keys,
            TokenIssuer issuer,
            TokenVerifier verifier,
            SessionService sessions) {}
}
