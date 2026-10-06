package com.mstech.vitrin.core.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SigningKeysTests {
    @Test
    void loadsGeneratedSigningFile(@TempDir Path directory) throws Exception {
        Path file = TestKeyFiles.writeSigningKey(directory);
        SigningKeys keys = new SigningKeys(properties(file, List.of()), true);

        ECPublicKey publicKey = Objects.requireNonNull(keys.find(keys.activeKeyId()));
        String expectedId =
                new ECKey.Builder(Curve.P_256, publicKey).build().computeThumbprint().toString();

        assertThat(keys.activeKeyId()).isEqualTo(expectedId);
        assertThat(keys.find("unknown")).isNull();
    }

    @Test
    void findsActiveAndRetiredKeys(@TempDir Path directory) throws Exception {
        Path activeFile = TestKeyFiles.writeSigningKey(directory);
        KeyPair retiredPair = TestKeyFiles.generate("secp256r1");
        ECPublicKey retiredKey = (ECPublicKey) retiredPair.getPublic();
        Path retiredFile = TestKeyFiles.writePublicKey(directory, retiredKey);
        String retiredId =
                new ECKey.Builder(Curve.P_256, retiredKey).build().computeThumbprint().toString();

        SigningKeys keys = new SigningKeys(properties(activeFile, List.of(retiredFile)), true);

        assertThat(keys.find(keys.activeKeyId())).isNotNull();
        assertThat(keys.find(retiredId)).isEqualTo(retiredKey);
        assertThat(keys.find("unknown")).isNull();
    }

    @Test
    void publishesActiveKeyFirstAndOnlyPublicParameters(@TempDir Path directory) throws Exception {
        Path activeFile = TestKeyFiles.writeSigningKey(directory);
        KeyPair retiredPair = TestKeyFiles.generate("secp256r1");
        Path retiredFile =
                TestKeyFiles.writePublicKey(directory, (ECPublicKey) retiredPair.getPublic());
        SigningKeys keys = new SigningKeys(properties(activeFile, List.of(retiredFile)), true);

        JWKSet published = JWKSet.parse(keys.publicJwkSet());
        assertThat(published.getKeys()).hasSize(2);
        assertThat(published.getKeys().getFirst().getKeyID()).isEqualTo(keys.activeKeyId());

        for (JWK key : published.getKeys()) {
            Map<String, Object> json = key.toJSONObject();
            assertThat(json).containsOnlyKeys("kty", "crv", "x", "y", "kid", "use", "alg");
            assertThat(json)
                    .containsEntry("kty", "EC")
                    .containsEntry("crv", "P-256")
                    .containsEntry("use", "sig")
                    .containsEntry("alg", "ES256")
                    .doesNotContainKey("d");
            assertThat(key.isPrivate()).isFalse();
        }
    }

    @Test
    void rejectsFileWithoutPublicBlock(@TempDir Path directory) throws Exception {
        KeyPair pair = TestKeyFiles.generate("secp256r1");
        Path file =
                Files.writeString(
                        directory.resolve("private-only.pem"),
                        TestKeyFiles.pem("PRIVATE KEY", pair.getPrivate().getEncoded()),
                        StandardCharsets.UTF_8);

        assertRejected(properties(file, List.of()), "missing PEM block", pair);
    }

    @Test
    void rejectsFileWithoutPrivateBlock(@TempDir Path directory) throws Exception {
        KeyPair pair = TestKeyFiles.generate("secp256r1");
        Path file = TestKeyFiles.writePublicKey(directory, (ECPublicKey) pair.getPublic());

        assertRejected(properties(file, List.of()), "missing PEM block", pair);
    }

    @Test
    void rejectsDuplicatePemBlock(@TempDir Path directory) throws Exception {
        KeyPair pair = TestKeyFiles.generate("secp256r1");
        String privateBlock = TestKeyFiles.pem("PRIVATE KEY", pair.getPrivate().getEncoded());
        Path file =
                Files.writeString(
                        directory.resolve("duplicate.pem"),
                        privateBlock
                                + privateBlock
                                + TestKeyFiles.pem("PUBLIC KEY", pair.getPublic().getEncoded()),
                        StandardCharsets.UTF_8);

        assertRejected(properties(file, List.of()), "duplicate PEM block", pair);
    }

    @Test
    void rejectsMismatchedPair(@TempDir Path directory) throws Exception {
        KeyPair privatePair = TestKeyFiles.generate("secp256r1");
        KeyPair publicPair = TestKeyFiles.generate("secp256r1");
        Path file = TestKeyFiles.writePair(directory, privatePair, publicPair);

        assertRejected(
                properties(file, List.of()),
                "private and public key do not match",
                privatePair,
                publicPair);
    }

    @Test
    void rejectsNonP256SigningKey(@TempDir Path directory) throws Exception {
        KeyPair pair = TestKeyFiles.generate("secp384r1");
        Path file = TestKeyFiles.writePair(directory, pair, pair);

        assertRejected(properties(file, List.of()), "not P-256", pair);
    }

    @Test
    void rejectsNonP256RetiredKey(@TempDir Path directory) throws Exception {
        Path activeFile = TestKeyFiles.writeSigningKey(directory);
        KeyPair retiredPair = TestKeyFiles.generate("secp384r1");
        Path retiredFile =
                TestKeyFiles.writePublicKey(directory, (ECPublicKey) retiredPair.getPublic());

        assertRejected(properties(activeFile, List.of(retiredFile)), "not P-256", retiredPair);
    }

    @Test
    void rejectsMissingSigningFile(@TempDir Path directory) {
        assertRejected(properties(directory.resolve("missing.pem"), List.of()), "unreadable");
    }

    @Test
    void rejectsMissingRetiredFile(@TempDir Path directory) throws Exception {
        Path activeFile = TestKeyFiles.writeSigningKey(directory);

        assertRejected(
                properties(activeFile, List.of(directory.resolve("missing-public.pem"))),
                "unreadable");
    }

    @Test
    void rejectsRetiredFileContainingPrivateKey(@TempDir Path directory) throws Exception {
        Path activeFile = TestKeyFiles.writeSigningKey(directory);
        KeyPair retiredPair = TestKeyFiles.generate("secp256r1");
        Path retiredFile = TestKeyFiles.writePair(directory, retiredPair, retiredPair);

        assertRejected(
                properties(activeFile, List.of(retiredFile)),
                "contains a private key",
                retiredPair);
    }

    @Test
    void rejectsNonEcKey(@TempDir Path directory) throws Exception {
        java.security.KeyPairGenerator generator =
                java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        Path file = TestKeyFiles.writePair(directory, pair, pair);

        assertRejected(properties(file, List.of()), "not an EC key", pair);
    }

    @Test
    void refusesEphemeralKeyInProduction() {
        assertThatThrownBy(() -> new SigningKeys(properties(null, List.of()), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("vitrin.identity.signing-key-file is required");
    }

    @Test
    void generatesEphemeralKeyOutsideProduction() {
        SigningKeys keys = new SigningKeys(properties(null, List.of()), false);

        assertThat(keys.find(keys.activeKeyId())).isNotNull();
        assertThat(keys.publicJwkSet()).containsOnlyKeys("keys");
    }

    private static IdentityProperties properties(
            @Nullable Path signingFile, List<Path> retiredFiles) {
        return new IdentityProperties(
                signingFile,
                retiredFiles,
                "vitrin-core",
                "vitrin-api",
                Duration.ofMinutes(15),
                Duration.ofHours(24));
    }

    private static void assertRejected(
            IdentityProperties properties, String category, KeyPair... pairs) {
        assertThatThrownBy(() -> new SigningKeys(properties, false))
                .isInstanceOfSatisfying(
                        IllegalStateException.class,
                        exception -> {
                            assertThat(exception)
                                    .hasMessageContaining(category)
                                    .hasMessageContaining("vitrin.identity.");
                            assertThat(exception.getCause()).isNull();
                            String message = Objects.requireNonNull(exception.getMessage());
                            assertThat(message).doesNotContain("-----BEGIN");
                            for (KeyPair pair : pairs) {
                                assertNoKeyText(message, pair.getPrivate().getEncoded());
                                assertNoKeyText(message, pair.getPublic().getEncoded());
                            }
                        });
    }

    private static void assertNoKeyText(String message, byte[] encoded) {
        String base64 = Base64.getEncoder().encodeToString(encoded);
        assertThat(message).doesNotContain(base64).doesNotContain(base64.substring(0, 64));
    }
}
