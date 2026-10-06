package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MemoryQuotaStoreTests {
    @Test
    void allowsExactlyTheConfiguredCapacityThenRejects() {
        MemoryQuotaStore store = new MemoryQuotaStore(100);

        for (int index = 0; index < 3; index++) {
            QuotaDecision decision = store.tryConsume("key", 3);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.limit()).isEqualTo(3L);
            assertThat(decision.retryAfterSeconds()).isZero();
        }
        QuotaDecision rejected = store.tryConsume("key", 3);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.remaining()).isZero();
        assertThat(rejected.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
        assertThat(rejected.resetSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void remainingTokensCountDownToZero() {
        MemoryQuotaStore store = new MemoryQuotaStore(100);

        assertThat(store.tryConsume("key", 3).remaining()).isEqualTo(2L);
        assertThat(store.tryConsume("key", 3).remaining()).isEqualTo(1L);
        assertThat(store.tryConsume("key", 3).remaining()).isZero();
    }

    @Test
    void differentKeysHaveIndependentCounters() {
        MemoryQuotaStore store = new MemoryQuotaStore(100);
        assertThat(store.tryConsume("first", 1).allowed()).isTrue();
        assertThat(store.tryConsume("first", 1).allowed()).isFalse();

        assertThat(store.tryConsume("second", 1).allowed()).isTrue();
        assertThat(store.tryConsume("second", 1).allowed()).isFalse();
    }

    @Test
    void exceedingFallbackCapacityStillEnforcesNewCounters() {
        MemoryQuotaStore store = new MemoryQuotaStore(1);
        assertThat(store.tryConsume("first", 1).allowed()).isTrue();
        assertThat(store.tryConsume("second", 1).allowed()).isTrue();

        assertThat(store.tryConsume("third", 1).allowed()).isTrue();
        assertThat(store.tryConsume("third", 1).allowed()).isFalse();
        assertThat(store.tryConsume("fourth", 1).allowed()).isTrue();
        assertThat(store.tryConsume("fourth", 1).allowed()).isFalse();
    }
}
