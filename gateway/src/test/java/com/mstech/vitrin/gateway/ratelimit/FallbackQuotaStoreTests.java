package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.testing.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FallbackQuotaStoreTests {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
    private final FakePrimary primary = new FakePrimary();
    private final FallbackQuotaStore store =
            new FallbackQuotaStore(
                    primary, new MemoryQuotaStore(100), clock, Duration.ofSeconds(5));

    @Test
    void healthyPrimaryIsUsedWithoutDegradation() {
        QuotaDecision decision = store.tryConsume("key", 3);

        assertThat(decision).isEqualTo(primary.answer);
        assertThat(primary.calls.get()).isEqualTo(1);
        assertThat(store.degraded()).isFalse();
    }

    @Test
    void failedPrimaryUsesAnEnforcingFallback() {
        primary.fail = true;

        assertThat(store.tryConsume("key", 2).allowed()).isTrue();
        assertThat(store.tryConsume("key", 2).allowed()).isTrue();
        QuotaDecision rejected = store.tryConsume("key", 2);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(store.degraded()).isTrue();
        assertThat(primary.calls.get()).isEqualTo(1);
    }

    @Test
    void primaryIsNotTouchedInsideRetryInterval() {
        primary.fail = true;
        assertThat(store.tryConsume("first", 2).allowed()).isTrue();
        primary.fail = false;
        clock.advance(Duration.ofSeconds(4));

        assertThat(store.tryConsume("second", 2).allowed()).isTrue();
        assertThat(primary.calls.get()).isEqualTo(1);
        assertThat(store.degraded()).isTrue();
    }

    @Test
    void healthyRetryAfterIntervalClearsDegradation() {
        primary.fail = true;
        assertThat(store.tryConsume("key", 2).allowed()).isTrue();
        primary.fail = false;
        clock.advance(Duration.ofSeconds(6));

        assertThat(store.tryConsume("key", 2)).isEqualTo(primary.answer);
        assertThat(primary.calls.get()).isEqualTo(2);
        assertThat(store.degraded()).isFalse();
    }

    private static final class FakePrimary implements QuotaStore {
        private final AtomicInteger calls = new AtomicInteger();
        private final QuotaDecision answer = new QuotaDecision(true, 777, 776, 1, 0);
        private boolean fail;

        @Override
        public QuotaDecision tryConsume(String key, int limitPerMinute) {
            calls.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("Unavailable");
            }
            return answer;
        }
    }
}
