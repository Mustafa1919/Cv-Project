package com.mstech.vitrin.gateway.auth;

import com.mstech.vitrin.gateway.edge.GatewayProperties;
import com.mstech.vitrin.platform.token.VerificationKeySource;
import java.io.IOException;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JwksKeySource implements VerificationKeySource {
    private static final Logger LOGGER = LoggerFactory.getLogger(JwksKeySource.class);

    private final JwksClient client;
    private final Clock clock;
    private final Duration minRefreshInterval;
    private final Duration maxAge;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile State state = new State(null, null, false);

    public JwksKeySource(JwksClient client, Clock clock, GatewayProperties properties) {
        this.client = client;
        this.clock = clock;
        this.minRefreshInterval = properties.jwksMinRefreshInterval();
        this.maxAge = properties.jwksMaxAge();
    }

    @Override
    public @Nullable ECPublicKey find(String keyId) {
        State observed = state;
        if (needsRefresh(observed.snapshot(), keyId, clock.instant())) {
            refresh(keyId, false);
        }
        Snapshot snapshot = state.snapshot();
        return snapshot == null ? null : snapshot.keys().get(keyId);
    }

    public boolean unavailable() {
        State observed = state;
        return observed.snapshot() == null || observed.lastAttemptFailed();
    }

    public void warmUp() {
        refresh(null, true);
    }

    private boolean needsRefresh(@Nullable Snapshot snapshot, String keyId, Instant now) {
        return snapshot == null
                || Duration.between(snapshot.loadedAt(), now).compareTo(maxAge) > 0
                || !snapshot.keys().containsKey(keyId);
    }

    private void refresh(@Nullable String keyId, boolean warmUp) {
        refreshLock.lock();
        try {
            State observed = state;
            Instant now = clock.instant();
            Instant lastAttempt = observed.lastAttempt();
            if (lastAttempt != null
                    && Duration.between(lastAttempt, now).compareTo(minRefreshInterval) < 0) {
                return;
            }
            if (!warmUp && keyId != null && !needsRefresh(observed.snapshot(), keyId, now)) {
                return;
            }

            state = new State(observed.snapshot(), now, observed.lastAttemptFailed());
            try {
                Map<String, ECPublicKey> keys = Map.copyOf(client.fetch());
                if (keys.isEmpty()) {
                    throw new IOException("JWKS contains no supported keys");
                }
                Snapshot loaded = new Snapshot(keys, clock.instant());
                state = new State(loaded, now, false);
            } catch (IOException | RuntimeException exception) {
                state = new State(observed.snapshot(), now, true);
                LOGGER.warn("Key set refresh failed: {}", exception.getClass().getName());
            }
        } finally {
            refreshLock.unlock();
        }
    }

    private record Snapshot(Map<String, ECPublicKey> keys, Instant loadedAt) {
        private Snapshot {
            keys = Map.copyOf(keys);
        }
    }

    private record State(
            @Nullable Snapshot snapshot,
            @Nullable Instant lastAttempt,
            boolean lastAttemptFailed) {}
}
