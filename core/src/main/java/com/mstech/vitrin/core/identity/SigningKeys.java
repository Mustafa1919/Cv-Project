package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.VerificationKeySource;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SigningKeys implements VerificationKeySource {
    private static final Logger LOGGER = LoggerFactory.getLogger(SigningKeys.class);
    private static final Pattern PRIVATE_BLOCK =
            Pattern.compile(
                    "-----BEGIN PRIVATE KEY-----\\s*([A-Za-z0-9+/=\\s]+?)"
                            + "\\s*-----END PRIVATE KEY-----");
    private static final Pattern PUBLIC_BLOCK =
            Pattern.compile(
                    "-----BEGIN PUBLIC KEY-----\\s*([A-Za-z0-9+/=\\s]+?)"
                            + "\\s*-----END PUBLIC KEY-----");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ECPrivateKey privateKey;
    private final String activeKeyId;
    private final Map<String, ECPublicKey> verificationKeys;
    private final List<Map<String, Object>> publicKeys;

    SigningKeys(IdentityProperties properties, boolean production) {
        KeyPair active;
        Path signingFile = properties.signingKeyFile();
        if (signingFile == null) {
            if (production) {
                throw new IllegalStateException("vitrin.identity.signing-key-file is required");
            }
            active = generate();
            LOGGER.warn("Using an ephemeral signing key; tokens will not survive a restart");
        } else {
            active = loadSigning(signingFile);
        }
        privateKey = (ECPrivateKey) active.getPrivate();
        ECPublicKey activePublic = (ECPublicKey) active.getPublic();
        activeKeyId = thumbprint(activePublic);
        Map<String, ECPublicKey> all = new LinkedHashMap<>();
        all.put(activeKeyId, activePublic);
        for (Path retiredFile : properties.retiredPublicKeyFiles()) {
            ECPublicKey retired = loadPublic(retiredFile);
            all.putIfAbsent(thumbprint(retired), retired);
        }
        verificationKeys = Map.copyOf(all);
        List<Map<String, Object>> jwks = new ArrayList<>();
        all.forEach(
                (id, key) ->
                        jwks.add(
                                Map.copyOf(
                                        new ECKey.Builder(Curve.P_256, key)
                                                .keyID(id)
                                                .keyUse(KeyUse.SIGNATURE)
                                                .algorithm(JWSAlgorithm.ES256)
                                                .build()
                                                .toJSONObject())));
        publicKeys = List.copyOf(jwks);
    }

    String activeKeyId() {
        return activeKeyId;
    }

    void sign(JWSObject object) throws JOSEException {
        object.sign(new ECDSASigner(privateKey));
    }

    @Override
    public @Nullable ECPublicKey find(String keyId) {
        return verificationKeys.get(keyId);
    }

    Map<String, Object> publicJwkSet() {
        return Map.of("keys", publicKeys);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to generate an identity signing key");
        }
    }

    private static KeyPair loadSigning(Path file) {
        try {
            String pem = Files.readString(file, StandardCharsets.UTF_8);
            byte[] privateBytes = block(pem, PRIVATE_BLOCK);
            byte[] publicBytes = block(pem, PUBLIC_BLOCK);
            KeyFactory factory = KeyFactory.getInstance("EC");
            if (!(factory.generatePrivate(new PKCS8EncodedKeySpec(privateBytes))
                            instanceof ECPrivateKey privatePart)
                    || !(factory.generatePublic(new X509EncodedKeySpec(publicBytes))
                            instanceof ECPublicKey publicPart)) {
                throw new InvalidKeyFile(Category.NOT_EC);
            }
            requireP256(privatePart.getParams());
            requireP256(publicPart.getParams());
            byte[] probe = "vitrin-key-pair-check".getBytes(StandardCharsets.UTF_8);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privatePart);
            signature.update(probe);
            byte[] signed = signature.sign();
            signature.initVerify(publicPart);
            signature.update(probe);
            if (!signature.verify(signed)) {
                throw new InvalidKeyFile(Category.MISMATCH);
            }
            return new KeyPair(publicPart, privatePart);
        } catch (InvalidKeyFile exception) {
            throw invalidFile("vitrin.identity.signing-key-file", file, exception.category());
        } catch (IOException | SecurityException exception) {
            throw invalidFile("vitrin.identity.signing-key-file", file, Category.UNREADABLE);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw invalidFile("vitrin.identity.signing-key-file", file, Category.NOT_EC);
        }
    }

    private static ECPublicKey loadPublic(Path file) {
        try {
            String pem = Files.readString(file, StandardCharsets.UTF_8);
            if (pem.contains("-----BEGIN PRIVATE KEY-----")) {
                throw new InvalidKeyFile(Category.CONTAINS_PRIVATE);
            }
            if (!(KeyFactory.getInstance("EC")
                            .generatePublic(new X509EncodedKeySpec(block(pem, PUBLIC_BLOCK)))
                    instanceof ECPublicKey publicKey)) {
                throw new InvalidKeyFile(Category.NOT_EC);
            }
            requireP256(publicKey.getParams());
            return publicKey;
        } catch (InvalidKeyFile exception) {
            throw invalidFile(
                    "vitrin.identity.retired-public-key-files", file, exception.category());
        } catch (IOException | SecurityException exception) {
            throw invalidFile(
                    "vitrin.identity.retired-public-key-files", file, Category.UNREADABLE);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw invalidFile("vitrin.identity.retired-public-key-files", file, Category.NOT_EC);
        }
    }

    private static byte[] block(String pem, Pattern pattern) throws InvalidKeyFile {
        Matcher matcher = pattern.matcher(pem);
        if (!matcher.find()) {
            throw new InvalidKeyFile(Category.MISSING_BLOCK);
        }
        String encoded = matcher.group(1);
        if (matcher.find()) {
            throw new InvalidKeyFile(Category.DUPLICATE_BLOCK);
        }
        try {
            return Base64.getDecoder().decode(WHITESPACE.matcher(encoded).replaceAll(""));
        } catch (IllegalArgumentException exception) {
            throw new InvalidKeyFile(Category.NOT_EC);
        }
    }

    private static void requireP256(ECParameterSpec actual)
            throws GeneralSecurityException, InvalidKeyFile {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        if (!actual.getCurve().equals(expected.getCurve())
                || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder())
                || actual.getCofactor() != expected.getCofactor()) {
            throw new InvalidKeyFile(Category.NOT_P256);
        }
    }

    private static String thumbprint(ECPublicKey key) {
        try {
            return new ECKey.Builder(Curve.P_256, key).build().computeThumbprint().toString();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Unable to identify an identity public key");
        }
    }

    private static IllegalStateException invalidFile(
            String property, Path file, Category category) {
        // Parser causes can contain key material; retain only a fixed diagnostic category.
        return new IllegalStateException(
                "Invalid " + property + " file: " + file + " (" + category.description + ")");
    }

    private enum Category {
        UNREADABLE("unreadable"),
        MISSING_BLOCK("missing PEM block"),
        DUPLICATE_BLOCK("duplicate PEM block"),
        NOT_EC("not an EC key"),
        NOT_P256("not P-256"),
        MISMATCH("private and public key do not match"),
        CONTAINS_PRIVATE("contains a private key");

        private final String description;

        Category(String description) {
            this.description = description;
        }
    }

    private static final class InvalidKeyFile extends Exception {
        private static final long serialVersionUID = 1L;

        private final Category category;

        private InvalidKeyFile(Category category) {
            super(category.description);
            this.category = category;
        }

        private Category category() {
            return category;
        }
    }
}
