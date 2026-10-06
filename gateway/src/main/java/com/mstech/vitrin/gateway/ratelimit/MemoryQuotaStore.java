package com.mstech.vitrin.gateway.ratelimit;

import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public final class MemoryQuotaStore implements QuotaStore {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ReentrantLock insertionLock = new ReentrantLock();
    private final int maxEntries;

    public MemoryQuotaStore(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Invalid fallback quota capacity");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public QuotaDecision tryConsume(String key, int limitPerMinute) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("Invalid quota limit");
        }
        String bucketKey = key + '|' + limitPerMinute;
        Bucket bucket = buckets.get(bucketKey);
        if (bucket == null) {
            bucket = createBucket(bucketKey, limitPerMinute);
        }
        return QuotaDecisions.fromProbe(bucket.tryConsumeAndReturnRemaining(1), limitPerMinute);
    }

    private Bucket createBucket(String bucketKey, int limitPerMinute) {
        insertionLock.lock();
        try {
            Bucket existing = buckets.get(bucketKey);
            if (existing != null) {
                return existing;
            }
            if (buckets.size() > maxEntries) {
                // Degraded mode may briefly loosen limits when its bounded counters are discarded.
                buckets.clear();
            }
            Bucket created =
                    Bucket.builder()
                            .addLimit(
                                    limit ->
                                            limit.capacity(limitPerMinute)
                                                    .refillGreedy(
                                                            limitPerMinute, Duration.ofMinutes(1)))
                            .build();
            buckets.put(bucketKey, created);
            return created;
        } finally {
            insertionLock.unlock();
        }
    }
}
