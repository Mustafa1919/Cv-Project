package com.mstech.vitrin.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mstech.vitrin.gateway.testing.MutableClock;
import com.mstech.vitrin.gateway.testing.Rules;
import com.mstech.vitrin.gateway.testing.TestTokens;
import com.nimbusds.jose.JOSEException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JwksKeySourceTests {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakeClient client = new FakeClient();
    private final JwksKeySource source =
            new JwksKeySource(client, clock, Rules.gatewayProperties("CF-Connecting-IP"));

    private @Nullable TestTokens tokens;

    @BeforeEach
    void createKeys() throws GeneralSecurityException, JOSEException {
        tokens = new TestTokens();
        client.keys = keys().keyMap();
    }

    @Test
    void failedColdLoadReturnsNullAndReportsUnavailable() {
        client.failure = "io";

        assertThat(source.find(keys().keyId())).isNull();
        assertThat(source.unavailable()).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void knownKeyReusesSuccessfulSnapshot() {
        assertThat(source.find(keys().keyId())).isEqualTo(keys().publicKey());
        assertThat(source.unavailable()).isFalse();

        assertThat(source.find(keys().keyId())).isEqualTo(keys().publicKey());
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void unknownKeyCannotRefreshInsideMinimumInterval()
            throws GeneralSecurityException, JOSEException {
        TestTokens rotated = new TestTokens();
        assertThat(source.find(keys().keyId())).isNotNull();
        client.keys = rotated.keyMap();
        clock.advance(Duration.ofSeconds(9));

        assertThat(source.find(rotated.keyId())).isNull();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void rotationIsLoadedAfterMinimumInterval() throws GeneralSecurityException, JOSEException {
        TestTokens rotated = new TestTokens();
        assertThat(source.find(keys().keyId())).isNotNull();
        client.keys = rotated.keyMap();
        clock.advance(Duration.ofSeconds(11));

        assertThat(source.find(rotated.keyId())).isEqualTo(rotated.publicKey());
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void failedRefreshPreservesPreviouslyLoadedKeys() {
        assertThat(source.find(keys().keyId())).isNotNull();
        client.failure = "io";
        clock.advance(Duration.ofSeconds(11));

        assertThat(source.find("unknown")).isNull();
        assertThat(source.find(keys().keyId())).isEqualTo(keys().publicKey());
        assertThat(source.unavailable()).isTrue();
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void failedAttemptAlsoStartsTheMinimumInterval() {
        client.failure = "io";
        assertThat(source.find("unknown")).isNull();
        client.failure = "";
        clock.advance(Duration.ofSeconds(9));

        assertThat(source.find(keys().keyId())).isNull();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void successfulRetryClearsUnavailable() {
        client.failure = "io";
        assertThat(source.find("unknown")).isNull();
        client.failure = "";
        clock.advance(Duration.ofSeconds(11));

        assertThat(source.find(keys().keyId())).isEqualTo(keys().publicKey());
        assertThat(source.unavailable()).isFalse();
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    void expiredSnapshotRefreshesEvenForAKnownKey() throws GeneralSecurityException, JOSEException {
        TestTokens replacement = new TestTokens();
        assertThat(source.find(keys().keyId())).isNotNull();
        client.keys = replacement.keyMap();
        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(source.find(keys().keyId())).isNull();
        assertThat(client.calls.get()).isEqualTo(2);
        assertThat(source.find(replacement.keyId())).isEqualTo(replacement.publicKey());
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "runtime"})
    void warmUpNeverPropagatesClientFailure(String failure) {
        client.failure = failure;

        assertThatCode(source::warmUp).doesNotThrowAnyException();
        assertThat(source.unavailable()).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void concurrentUnknownKeyLookupsShareOneRefresh() throws Exception {
        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        client.entered = entered;
        client.release = release;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<@Nullable ECPublicKey>> results = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                results.add(
                        executor.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(5, TimeUnit.SECONDS)) {
                                        throw new IllegalStateException("Start latch timed out");
                                    }
                                    return source.find("unknown");
                                }));
            }
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(client.calls.get()).isEqualTo(1);
            } finally {
                start.countDown();
                release.countDown();
            }
            for (Future<@Nullable ECPublicKey> result : results) {
                assertThat(result.get(5, TimeUnit.SECONDS)).isNull();
            }
        }
        assertThat(client.calls.get()).isEqualTo(1);
    }

    private TestTokens keys() {
        return java.util.Objects.requireNonNull(tokens);
    }

    private static final class FakeClient implements JwksClient {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Map<String, ECPublicKey> keys = Map.of();
        private volatile String failure = "";
        private volatile @Nullable CountDownLatch entered;
        private volatile @Nullable CountDownLatch release;

        @Override
        public Map<String, ECPublicKey> fetch() throws IOException {
            calls.incrementAndGet();
            CountDownLatch enteredLatch = entered;
            CountDownLatch releaseLatch = release;
            if (enteredLatch != null) {
                enteredLatch.countDown();
            }
            if (releaseLatch != null) {
                try {
                    if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("Release latch timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted");
                }
            }
            if ("io".equals(failure)) {
                throw new IOException("Unavailable");
            }
            if ("runtime".equals(failure)) {
                throw new IllegalStateException("Unavailable");
            }
            return keys;
        }
    }
}
