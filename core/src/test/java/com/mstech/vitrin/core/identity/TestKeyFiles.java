package com.mstech.vitrin.core.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

public final class TestKeyFiles {
    private TestKeyFiles() {}

    static KeyPair generate(String curve) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    public static Path writeSigningKey(Path dir) throws IOException, GeneralSecurityException {
        KeyPair pair = generate("secp256r1");
        return writePair(dir, pair, pair);
    }

    static Path writePublicKey(Path dir, ECPublicKey key) throws IOException {
        Path file = Files.createTempFile(dir, "public-", ".pem");
        return Files.writeString(file, pem("PUBLIC KEY", key.getEncoded()), StandardCharsets.UTF_8);
    }

    static Path writePair(Path dir, KeyPair privatePair, KeyPair publicPair) throws IOException {
        Path file = Files.createTempFile(dir, "signing-", ".pem");
        return Files.writeString(
                file,
                pem("PRIVATE KEY", privatePair.getPrivate().getEncoded())
                        + pem("PUBLIC KEY", publicPair.getPublic().getEncoded()),
                StandardCharsets.UTF_8);
    }

    static String pem(String label, byte[] encoded) {
        return "-----BEGIN "
                + label
                + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(encoded)
                + "\n-----END "
                + label
                + "-----\n";
    }
}
