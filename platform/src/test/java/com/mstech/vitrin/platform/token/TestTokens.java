package com.mstech.vitrin.platform.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jose.util.JSONObjectUtils;
import com.nimbusds.jwt.SignedJWT;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TestTokens {
    static final Instant NOW = Instant.parse("2026-10-05T11:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final String SID = "0123456789abcdef0123456789abcdef";

    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String keyId;

    TestTokens() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair pair = generator.generateKeyPair();
            privateKey = (ECPrivateKey) pair.getPrivate();
            publicKey = (ECPublicKey) pair.getPublic();
            keyId =
                    new ECKey.Builder(Curve.P_256, publicKey)
                            .build()
                            .computeThumbprint()
                            .toString();
        } catch (GeneralSecurityException | JOSEException exception) {
            throw new IllegalStateException("Unable to generate test key", exception);
        }
    }

    ECPublicKey publicKey() {
        return publicKey;
    }

    String keyId() {
        return keyId;
    }

    TokenVerifier verifier(Clock clock) {
        return new TokenVerifier(
                "vitrin-core", "vitrin-api", clock, id -> keyId.equals(id) ? publicKey : null);
    }

    static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "vitrin-core");
        claims.put("aud", List.of("vitrin-api"));
        claims.put("sid", SID);
        claims.put("role", Role.GUEST_COMPANY.claimValue());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(900).getEpochSecond());
        return claims;
    }

    String sign(TokenKind kind, Map<String, Object> claims) throws JOSEException {
        return sign(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(new JOSEObjectType(kind.headerType()))
                        .keyID(keyId)
                        .build(),
                claims);
    }

    String sign(JWSHeader header, Map<String, Object> claims) throws JOSEException {
        JWSObject object = new JWSObject(header, new Payload(claims));
        object.sign(new ECDSASigner(privateKey));
        return object.serialize();
    }

    static String tamperSignature(String compact) {
        int start = compact.lastIndexOf('.') + 1;
        int index = start + (compact.length() - start) / 2;
        char replacement = compact.charAt(index) == 'A' ? 'B' : 'A';
        return compact.substring(0, index) + replacement + compact.substring(index + 1);
    }

    static String changeRole(String compact) throws ParseException {
        SignedJWT jwt = SignedJWT.parse(compact);
        Map<String, Object> claims = new LinkedHashMap<>(jwt.getPayload().toJSONObject());
        claims.put("role", Role.DEMO_CANDIDATE.claimValue());
        return jwt.getHeader().toBase64URL()
                + "."
                + Base64URL.encode(JSONObjectUtils.toJSONString(claims))
                + "."
                + jwt.getSignature();
    }
}
