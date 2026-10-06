package com.mstech.vitrin.platform.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TokenVerifierTests {
    private final TestTokens tokens = new TestTokens();
    private final TokenVerifier verifier = tokens.verifier(TestTokens.CLOCK);

    @Test
    void acceptsAccess() throws Exception {
        String compact = tokens.sign(TokenKind.ACCESS, TestTokens.claims());
        assertThat(verifier.verify(compact, TokenKind.ACCESS))
                .isEqualTo(
                        new VerifiedToken(
                                TokenKind.ACCESS,
                                TestTokens.SID,
                                Role.GUEST_COMPANY,
                                TestTokens.NOW,
                                TestTokens.NOW.plusSeconds(900)));
    }

    @Test
    void acceptsRefresh() throws Exception {
        Map<String, Object> claims = TestTokens.claims();
        claims.put("role", Role.DEMO_CANDIDATE.claimValue());
        String compact = tokens.sign(TokenKind.REFRESH, claims);
        assertThat(verifier.verify(compact, TokenKind.REFRESH))
                .isEqualTo(
                        new VerifiedToken(
                                TokenKind.REFRESH,
                                TestTokens.SID,
                                Role.DEMO_CANDIDATE,
                                TestTokens.NOW,
                                TestTokens.NOW.plusSeconds(900)));
    }

    @Test
    void rejectsMalformed() {
        reject(null, TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
        reject("", TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
        reject(" \t ", TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
        reject("not-a-jwt", TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
        reject("a".repeat(2049), TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
        reject("e30.e30.%%%%", TokenKind.ACCESS, TokenRejectedException.Reason.MALFORMED);
    }

    @Test
    void rejectsUnsupportedAlgorithmAndAlgorithmConfusion() throws Exception {
        PlainJWT unsigned = new PlainJWT(new JWTClaimsSet.Builder().issuer("vitrin-core").build());
        reject(
                unsigned.serialize(),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.UNSUPPORTED_ALGORITHM);
        JWSObject hmac =
                new JWSObject(
                        new JWSHeader.Builder(JWSAlgorithm.HS256)
                                .type(new JOSEObjectType(TokenKind.ACCESS.headerType()))
                                .keyID(tokens.keyId())
                                .build(),
                        new Payload(TestTokens.claims()));
        hmac.sign(new MACSigner(tokens.publicKey().getEncoded()));
        reject(
                hmac.serialize(),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.UNSUPPORTED_ALGORITHM);
    }

    @Test
    void rejectsWrongTypeInBothDirectionsAndMissingType() throws Exception {
        reject(
                tokens.sign(TokenKind.ACCESS, TestTokens.claims()),
                TokenKind.REFRESH,
                TokenRejectedException.Reason.WRONG_TYPE);
        reject(
                tokens.sign(TokenKind.REFRESH, TestTokens.claims()),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.WRONG_TYPE);
        reject(
                tokens.sign(
                        new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(tokens.keyId()).build(),
                        TestTokens.claims()),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.WRONG_TYPE);
    }

    @Test
    void rejectsUnknownAndMissingKey() throws Exception {
        for (String keyId : List.of("unknown", "")) {
            JWSHeader.Builder header =
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .type(new JOSEObjectType(TokenKind.ACCESS.headerType()));
            if (!keyId.isEmpty()) {
                header.keyID(keyId);
            }
            reject(
                    tokens.sign(header.build(), TestTokens.claims()),
                    TokenKind.ACCESS,
                    TokenRejectedException.Reason.UNKNOWN_KEY);
        }
    }

    @Test
    void rejectsBadSignaturesAndUnsignedPayloadChanges() throws Exception {
        String compact = tokens.sign(TokenKind.ACCESS, TestTokens.claims());
        reject(
                TestTokens.tamperSignature(compact),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.BAD_SIGNATURE);
        reject(
                TestTokens.changeRole(compact),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.BAD_SIGNATURE);
        TestTokens differentKey = new TestTokens();
        reject(
                differentKey.sign(
                        new JWSHeader.Builder(JWSAlgorithm.ES256)
                                .type(new JOSEObjectType(TokenKind.ACCESS.headerType()))
                                .keyID(tokens.keyId())
                                .build(),
                        TestTokens.claims()),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.BAD_SIGNATURE);
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        Map<String, Object> claims = TestTokens.claims();
        claims.put("iss", "different");
        reject(
                tokens.sign(TokenKind.ACCESS, claims),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.WRONG_ISSUER);
    }

    @Test
    void rejectsWrongAudienceAndMultipleAudiences() throws Exception {
        for (Object audience :
                List.of(List.of("different"), List.of("vitrin-api", "other"), "vitrin-api")) {
            Map<String, Object> claims = TestTokens.claims();
            claims.put("aud", audience);
            reject(
                    tokens.sign(TokenKind.ACCESS, claims),
                    TokenKind.ACCESS,
                    TokenRejectedException.Reason.WRONG_AUDIENCE);
        }
    }

    @Test
    void rejectsInvalidClaims() throws Exception {
        for (String claim : List.of("sid", "iat", "exp", "role")) {
            Map<String, Object> claims = TestTokens.claims();
            claims.remove(claim);
            reject(
                    tokens.sign(TokenKind.ACCESS, claims),
                    TokenKind.ACCESS,
                    TokenRejectedException.Reason.INVALID_CLAIMS);
        }
        for (Object session : List.of("A".repeat(32), "a".repeat(31), 42, true, List.of("a"))) {
            Map<String, Object> claims = TestTokens.claims();
            claims.put("sid", session);
            reject(
                    tokens.sign(TokenKind.ACCESS, claims),
                    TokenKind.ACCESS,
                    TokenRejectedException.Reason.INVALID_CLAIMS);
        }
        for (String claim : List.of("iat", "exp")) {
            for (Object value : List.of("123", true, new BigDecimal("123.5"))) {
                Map<String, Object> claims = TestTokens.claims();
                claims.put(claim, value);
                reject(
                        tokens.sign(TokenKind.ACCESS, claims),
                        TokenKind.ACCESS,
                        TokenRejectedException.Reason.INVALID_CLAIMS);
            }
        }
        Map<String, Object> claims = TestTokens.claims();
        claims.put("exp", TestTokens.NOW.getEpochSecond());
        reject(
                tokens.sign(TokenKind.ACCESS, claims),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.INVALID_CLAIMS);
        claims.put("exp", TestTokens.NOW.minusSeconds(1).getEpochSecond());
        reject(
                tokens.sign(TokenKind.ACCESS, claims),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.INVALID_CLAIMS);
        claims = TestTokens.claims();
        claims.put("role", 1);
        reject(
                tokens.sign(TokenKind.ACCESS, claims),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.INVALID_CLAIMS);
    }

    @Test
    void rejectsUnknownRole() throws Exception {
        for (String role : List.of("owner", "GUEST_COMPANY", "Guest_company")) {
            Map<String, Object> claims = TestTokens.claims();
            claims.put("role", role);
            reject(
                    tokens.sign(TokenKind.ACCESS, claims),
                    TokenKind.ACCESS,
                    TokenRejectedException.Reason.UNKNOWN_ROLE);
        }
        assertThat(Role.fromClaim("guest_company")).contains(Role.GUEST_COMPANY);
        assertThat(Role.fromClaim("demo_candidate")).contains(Role.DEMO_CANDIDATE);
        assertThat(Role.fromClaim("GUEST_COMPANY")).isEmpty();
    }

    @Test
    void rejectsNotYetValid() throws Exception {
        Map<String, Object> claims = TestTokens.claims();
        claims.put("iat", TestTokens.NOW.plusSeconds(1).getEpochSecond());
        reject(
                tokens.sign(TokenKind.ACCESS, claims),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.NOT_YET_VALID);
    }

    @Test
    void expiresExactlyAtExpiration() throws Exception {
        String compact = tokens.sign(TokenKind.ACCESS, TestTokens.claims());
        TokenVerifier before =
                tokens.verifier(Clock.fixed(TestTokens.NOW.plusSeconds(899), ZoneOffset.UTC));
        assertThat(before.verify(compact, TokenKind.ACCESS).expiresAt())
                .isEqualTo(TestTokens.NOW.plusSeconds(900));
        TokenVerifier atExpiration =
                tokens.verifier(Clock.fixed(TestTokens.NOW.plusSeconds(900), ZoneOffset.UTC));
        assertThatThrownBy(() -> atExpiration.verify(compact, TokenKind.ACCESS))
                .isInstanceOfSatisfying(
                        TokenRejectedException.class,
                        exception ->
                                assertThat(exception.reason())
                                        .isEqualTo(TokenRejectedException.Reason.EXPIRED));
    }

    @Test
    void firstFailureWins() throws Exception {
        Map<String, Object> claims = TestTokens.claims();
        claims.put("iss", "wrong");
        claims.put("aud", List.of("wrong"));
        claims.remove("sid");
        String compact = tokens.sign(TokenKind.ACCESS, claims);
        reject(
                TestTokens.tamperSignature(compact),
                TokenKind.ACCESS,
                TokenRejectedException.Reason.BAD_SIGNATURE);
        reject(compact, TokenKind.ACCESS, TokenRejectedException.Reason.WRONG_ISSUER);
    }

    private void reject(
            @Nullable String compact, TokenKind kind, TokenRejectedException.Reason reason) {
        assertThatThrownBy(() -> verifier.verify(compact, kind))
                .isInstanceOfSatisfying(
                        TokenRejectedException.class,
                        exception -> {
                            assertThat(exception.reason()).isEqualTo(reason);
                            assertThat(exception).hasMessage(reason.name());
                        });
    }
}
