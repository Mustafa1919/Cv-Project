package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AddressHasherTests {
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    @TempDir private @Nullable Path directory;

    @Test
    void hashIsThirtyTwoLowercaseHexCharacters() throws IOException {
        AddressHasher hasher = AddressHasher.load(keyFile("key", KEY), true);

        assertThat(hasher.hash("203.0.113.7")).matches("^[0-9a-f]{32}$");
    }

    @Test
    void sameKeyAndAddressProduceTheSameHash() throws IOException {
        Path file = keyFile("key", KEY);
        AddressHasher first = AddressHasher.load(file, true);
        AddressHasher second = AddressHasher.load(file, true);

        assertThat(first.hash("203.0.113.7")).isEqualTo(second.hash("203.0.113.7"));
    }

    @Test
    void differentAddressesProduceDifferentHashes() throws IOException {
        AddressHasher hasher = AddressHasher.load(keyFile("key", KEY), true);

        assertThat(hasher.hash("203.0.113.7")).isNotEqualTo(hasher.hash("203.0.113.8"));
    }

    @Test
    void differentKeyFilesProduceDifferentHashes() throws IOException {
        AddressHasher first = AddressHasher.load(keyFile("first", KEY), true);
        AddressHasher second =
                AddressHasher.load(keyFile("second", "abcdef0123456789abcdef0123456789"), true);

        assertThat(first.hash("203.0.113.7")).isNotEqualTo(second.hash("203.0.113.7"));
    }

    @Test
    void hashDoesNotExposeTheCanonicalAddress() throws IOException {
        AddressHasher hasher = AddressHasher.load(keyFile("key", KEY), true);

        assertThat(hasher.hash("203.0.113.7")).doesNotContain("203.0.113.7");
    }

    @Test
    void shortKeyIsRejectedWithoutDisclosingItsContent() throws IOException {
        String secret = "short-secret-content";
        Path file = keyFile("short", secret);

        assertThatIllegalStateException()
                .isThrownBy(() -> AddressHasher.load(file, true))
                .withMessageContaining("vitrin.ratelimit.address-hash-key-file")
                .satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(secret));
    }

    @Test
    void surroundingAsciiWhitespaceIsTrimmed() throws IOException {
        AddressHasher plain = AddressHasher.load(keyFile("plain", KEY), true);
        AddressHasher padded = AddressHasher.load(keyFile("padded", " \t\r" + KEY + "\n"), true);

        assertThat(padded.hash("203.0.113.7")).isEqualTo(plain.hash("203.0.113.7"));
    }

    @Test
    void missingConfiguredFileIsRejectedInProduction() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AddressHasher.load(null, true))
                .withMessageContaining("vitrin.ratelimit.address-hash-key-file");
    }

    @Test
    void absentFileOutsideProductionGeneratesAUsableKey() {
        AddressHasher hasher = AddressHasher.load(null, false);

        assertThat(hasher.hash("203.0.113.7")).matches("^[0-9a-f]{32}$");
        assertThat(hasher.hash("203.0.113.7")).isEqualTo(hasher.hash("203.0.113.7"));
    }

    @Test
    void unreadablePathIsRejected() {
        Path missing = Objects.requireNonNull(directory).resolve("does-not-exist");

        assertThatIllegalStateException()
                .isThrownBy(() -> AddressHasher.load(missing, false))
                .withMessageContaining("vitrin.ratelimit.address-hash-key-file");
    }

    private Path keyFile(String name, String content) throws IOException {
        return Files.writeString(
                Objects.requireNonNull(directory).resolve(name),
                content,
                StandardCharsets.US_ASCII);
    }
}
