package com.mstech.vitrin.gateway.testing;

import com.mstech.vitrin.platform.token.Role;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.PlainObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class TestTokens {
    private final ECPublicKey publicKey;
    private final ECPrivateKey privateKey;
    private final ECKey publicJwk;
    private final String keyId;

    public TestTokens() throws GeneralSecurityException, JOSEException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        publicKey = (ECPublicKey) pair.getPublic();
        privateKey = (ECPrivateKey) pair.getPrivate();
        ECKey unnamed = new ECKey.Builder(Curve.P_256, publicKey).build();
        keyId = unnamed.computeThumbprint().toString();
        publicJwk = new ECKey.Builder(Curve.P_256, publicKey).keyID(keyId).build();
    }

    public String keyId() {
        return keyId;
    }

    public ECPublicKey publicKey() {
        return publicKey;
    }

    public Map<String, ECPublicKey> keyMap() {
        return Map.of(keyId, publicKey);
    }

    public String jwksJson() {
        return new JWKSet(publicJwk).toString();
    }

    public String access(String sessionId, Role role, Instant issuedAt, Instant expiresAt)
            throws JOSEException {
        return signed("at+jwt", "vitrin-core", "vitrin-api", sessionId, role, issuedAt, expiresAt);
    }

    public String signed(
            String type,
            String issuer,
            String audience,
            String sessionId,
            Role role,
            Instant issuedAt,
            Instant expiresAt)
            throws JOSEException {
        JWSHeader header =
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(new JOSEObjectType(type))
                        .keyID(keyId)
                        .build();
        JWSObject jws =
                new JWSObject(
                        header,
                        new Payload(
                                claims(issuer, audience, sessionId, role, issuedAt, expiresAt)));
        jws.sign(new ECDSASigner(privateKey));
        return jws.serialize();
    }

    public static String unsigned(
            String sessionId, Role role, Instant issuedAt, Instant expiresAt) {
        return new PlainObject(
                        new Payload(
                                claims(
                                        "vitrin-core",
                                        "vitrin-api",
                                        sessionId,
                                        role,
                                        issuedAt,
                                        expiresAt)))
                .serialize();
    }

    public static String tamperSignature(String compact) {
        String[] parts = parts(compact);
        String signature = parts[2];
        if (signature.length() < 3) {
            throw new IllegalArgumentException("Signature is too short");
        }
        int middle = signature.length() / 2;
        char replacement = signature.charAt(middle) == 'A' ? 'B' : 'A';
        parts[2] = signature.substring(0, middle) + replacement + signature.substring(middle + 1);
        return String.join(".", parts);
    }

    public static String changeRoleWithoutResigning(String compact, String newRoleClaim) {
        String[] parts = parts(compact);
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode payload = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        if (!(payload instanceof ObjectNode object)) {
            throw new IllegalArgumentException("JWT payload is not an object");
        }
        object.put("role", newRoleClaim);
        parts[1] =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(mapper.writeValueAsBytes(object));
        return String.join(".", parts);
    }

    // A plain map: Nimbus' JWTClaimsSet writes a single audience as a string, core writes an array.
    private static Map<String, Object> claims(
            String issuer,
            String audience,
            String sessionId,
            Role role,
            Instant issuedAt,
            Instant expiresAt) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", List.of(audience));
        claims.put("sid", sessionId);
        claims.put("role", role.claimValue());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        return claims;
    }

    private static String[] parts(String compact) {
        String[] parts = compact.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid compact JWT");
        }
        return parts;
    }
}
