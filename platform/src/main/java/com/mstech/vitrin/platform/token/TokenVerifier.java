package com.mstech.vitrin.platform.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.security.interfaces.ECPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class TokenVerifier {
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{32}");

    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final VerificationKeySource keys;

    public TokenVerifier(String issuer, String audience, Clock clock, VerificationKeySource keys) {
        this.issuer = Objects.requireNonNull(issuer);
        this.audience = Objects.requireNonNull(audience);
        this.clock = Objects.requireNonNull(clock);
        this.keys = Objects.requireNonNull(keys);
    }

    public VerifiedToken verify(@Nullable String compact, TokenKind expected)
            throws TokenRejectedException {
        if (compact == null || compact.isBlank() || compact.length() > 2048) {
            throw rejected(TokenRejectedException.Reason.MALFORMED);
        }

        JWT jwt;
        try {
            jwt = JWTParser.parse(compact);
        } catch (ParseException | RuntimeException exception) {
            // Parser details can include attacker-controlled token contents.
            throw rejected(TokenRejectedException.Reason.MALFORMED);
        }
        return verifyParsed(jwt, expected);
    }

    private VerifiedToken verifyParsed(JWT jwt, TokenKind expected) throws TokenRejectedException {
        if (!(jwt instanceof SignedJWT signed)
                || !JWSAlgorithm.ES256.equals(signed.getHeader().getAlgorithm())) {
            throw rejected(TokenRejectedException.Reason.UNSUPPORTED_ALGORITHM);
        }
        if (signed.getHeader().getType() == null
                || !expected.headerType().equals(signed.getHeader().getType().toString())) {
            throw rejected(TokenRejectedException.Reason.WRONG_TYPE);
        }
        String keyId = signed.getHeader().getKeyID();
        ECPublicKey key = keyId == null ? null : keys.find(keyId);
        if (key == null) {
            throw rejected(TokenRejectedException.Reason.UNKNOWN_KEY);
        }
        try {
            if (!signed.verify(new ECDSAVerifier(key))) {
                throw rejected(TokenRejectedException.Reason.BAD_SIGNATURE);
            }
        } catch (JOSEException exception) {
            throw rejected(TokenRejectedException.Reason.BAD_SIGNATURE);
        }

        Map<String, Object> claims;
        try {
            // Read raw JSON so Nimbus cannot coerce incorrectly typed claims.
            claims = JSONObjectUtils.parse(signed.getPayload().toString());
        } catch (ParseException | RuntimeException exception) {
            throw rejected(TokenRejectedException.Reason.MALFORMED);
        }
        if (!issuer.equals(claims.get("iss"))) {
            throw rejected(TokenRejectedException.Reason.WRONG_ISSUER);
        }
        Object audiences = claims.get("aud");
        if (!(audiences instanceof List<?> values)
                || values.size() != 1
                || !audience.equals(values.getFirst())) {
            throw rejected(TokenRejectedException.Reason.WRONG_AUDIENCE);
        }

        Object sessionClaim = claims.get("sid");
        Object roleClaim = claims.get("role");
        if (!(sessionClaim instanceof String sessionId)
                || !SESSION_ID.matcher(sessionId).matches()
                || !(roleClaim instanceof String roleValue)) {
            throw rejected(TokenRejectedException.Reason.INVALID_CLAIMS);
        }
        Instant issuedAt = numericDate(claims.get("iat"));
        Instant expiresAt = numericDate(claims.get("exp"));
        if (!expiresAt.isAfter(issuedAt)) {
            throw rejected(TokenRejectedException.Reason.INVALID_CLAIMS);
        }
        Role role =
                Role.fromClaim(roleValue)
                        .orElseThrow(() -> rejected(TokenRejectedException.Reason.UNKNOWN_ROLE));
        Instant now = clock.instant();
        if (issuedAt.isAfter(now)) {
            throw rejected(TokenRejectedException.Reason.NOT_YET_VALID);
        }
        if (!now.isBefore(expiresAt)) {
            throw rejected(TokenRejectedException.Reason.EXPIRED);
        }
        return new VerifiedToken(expected, sessionId, role, issuedAt, expiresAt);
    }

    private static Instant numericDate(@Nullable Object value) throws TokenRejectedException {
        if (!(value instanceof Number number)) {
            throw rejected(TokenRejectedException.Reason.INVALID_CLAIMS);
        }
        try {
            long seconds = new BigDecimal(number.toString()).longValueExact();
            return Instant.ofEpochSecond(seconds);
        } catch (RuntimeException exception) {
            throw rejected(TokenRejectedException.Reason.INVALID_CLAIMS);
        }
    }

    private static TokenRejectedException rejected(TokenRejectedException.Reason reason) {
        return new TokenRejectedException(reason);
    }
}
