package com.mstech.vitrin.gateway.ratelimit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

public final class AddressHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROPERTY = "vitrin.ratelimit.address-hash-key-file";

    private final SecretKeySpec key;

    private AddressHasher(byte[] keyBytes) {
        this.key = new SecretKeySpec(keyBytes, ALGORITHM);
    }

    public static AddressHasher load(@Nullable Path file, boolean production) {
        if (file == null) {
            if (production) {
                throw new IllegalStateException("Missing " + PROPERTY);
            }
            byte[] randomKey = new byte[32];
            RANDOM.nextBytes(randomKey);
            return new AddressHasher(randomKey);
        }

        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | SecurityException exception) {
            throw new IllegalStateException("Cannot read " + PROPERTY);
        }
        int start = 0;
        int end = bytes.length;
        while (start < end && asciiWhitespace(bytes[start])) {
            start++;
        }
        while (end > start && asciiWhitespace(bytes[end - 1])) {
            end--;
        }
        if (end - start < 32) {
            throw new IllegalStateException("Invalid " + PROPERTY);
        }
        return new AddressHasher(Arrays.copyOfRange(bytes, start, end));
    }

    public String hash(String canonicalAddress) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(canonicalAddress.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 unavailable");
        }
    }

    private static boolean asciiWhitespace(byte value) {
        return value == 0x20 || (value >= 0x09 && value <= 0x0d);
    }
}
