package com.mstech.vitrin.platform.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

class TokenVerifierPropertyTests {
    private final TestTokens tokens = new TestTokens();
    private final TokenVerifier verifier = tokens.verifier(TestTokens.CLOCK);

    @Provide
    Arbitrary<String> arbitraryInputs() {
        return Arbitraries.strings().all().ofMaxLength(3000);
    }

    @Provide
    Arbitrary<String> sessionIds() {
        return Arbitraries.strings().withChars("0123456789abcdef").ofLength(32);
    }

    @Property
    void arbitraryInputCannotEscapeAsAnotherException(@ForAll("arbitraryInputs") String compact) {
        try {
            VerifiedToken result = verifier.verify(compact, TokenKind.ACCESS);
            assertThat(result.kind()).isEqualTo(TokenKind.ACCESS);
        } catch (TokenRejectedException exception) {
            assertThat(exception.reason()).isNotNull();
        }
    }

    @Property
    void missingOrWronglyTypedRequiredClaimIsRejected(
            @ForAll @IntRange(min = 0, max = 6) int claimIndex,
            @ForAll @IntRange(min = 0, max = 5) int replacementIndex)
            throws Exception {
        String claim = List.of("iss", "aud", "sid", "role", "iat", "exp", "role").get(claimIndex);
        Map<String, Object> claims = TestTokens.claims();
        if (replacementIndex == 0) {
            claims.remove(claim);
        } else {
            Object replacement =
                    switch (replacementIndex) {
                        case 1 -> "incorrect";
                        case 2 ->
                                claim.equals("iat") || claim.equals("exp")
                                        ? new BigDecimal("1.5")
                                        : 42;
                        case 3 -> true;
                        case 4 -> List.of("incorrect");
                        default -> Map.of("incorrect", true);
                    };
            claims.put(claim, replacement);
        }
        String compact = tokens.sign(TokenKind.ACCESS, claims);
        assertThatThrownBy(() -> verifier.verify(compact, TokenKind.ACCESS))
                .isInstanceOfSatisfying(
                        TokenRejectedException.class,
                        exception ->
                                assertThat(exception.reason())
                                        .isIn(
                                                TokenRejectedException.Reason.WRONG_ISSUER,
                                                TokenRejectedException.Reason.WRONG_AUDIENCE,
                                                TokenRejectedException.Reason.INVALID_CLAIMS,
                                                TokenRejectedException.Reason.UNKNOWN_ROLE));
    }

    @Property
    void signedTokensRoundTripExactlyWithinTheirValidityInterval(
            @ForAll("sessionIds") String sessionId,
            @ForAll Role role,
            @ForAll TokenKind kind,
            @ForAll @IntRange(min = -200, max = 200) int issuedOffset,
            @ForAll @IntRange(min = 1, max = 400) int lifetime)
            throws Exception {
        Instant issuedAt = TestTokens.NOW.plusSeconds(issuedOffset);
        Instant expiresAt = issuedAt.plusSeconds(lifetime);
        Map<String, Object> claims = TestTokens.claims();
        claims.put("sid", sessionId);
        claims.put("role", role.claimValue());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        String compact = tokens.sign(kind, claims);
        if (!issuedAt.isAfter(TestTokens.NOW) && TestTokens.NOW.isBefore(expiresAt)) {
            assertThat(verifier.verify(compact, kind))
                    .isEqualTo(new VerifiedToken(kind, sessionId, role, issuedAt, expiresAt));
        } else {
            TokenRejectedException.Reason expected =
                    issuedAt.isAfter(TestTokens.NOW)
                            ? TokenRejectedException.Reason.NOT_YET_VALID
                            : TokenRejectedException.Reason.EXPIRED;
            assertThatThrownBy(() -> verifier.verify(compact, kind))
                    .isInstanceOfSatisfying(
                            TokenRejectedException.class,
                            exception -> assertThat(exception.reason()).isEqualTo(expected));
        }
    }
}
