package com.mstech.vitrin.gateway.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class RedisQuotaStore implements QuotaStore, AutoCloseable {
    private final Supplier<StatefulRedisConnection<String, byte[]>> connections;
    private final Duration timeout;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private volatile @Nullable Resources resources;
    private volatile boolean closed;

    public RedisQuotaStore(
            Supplier<StatefulRedisConnection<String, byte[]>> connections, Duration timeout) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Invalid Redis quota timeout");
        }
    }

    @Override
    public QuotaDecision tryConsume(String key, int limitPerMinute) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("Invalid quota limit");
        }
        ProxyManager<String> manager = resources().manager();
        BucketConfiguration configuration =
                BucketConfiguration.builder()
                        .addLimit(
                                limit ->
                                        limit.capacity(limitPerMinute)
                                                .refillGreedy(
                                                        limitPerMinute, Duration.ofMinutes(1)))
                        .build();
        return QuotaDecisions.fromProbe(
                manager.builder().build(key, () -> configuration).tryConsumeAndReturnRemaining(1),
                limitPerMinute);
    }

    private Resources resources() {
        if (closed) {
            throw new IllegalStateException("Redis quota store is closed");
        }
        Resources existing = resources;
        if (existing != null) {
            return existing;
        }
        lifecycleLock.lock();
        try {
            if (closed) {
                throw new IllegalStateException("Redis quota store is closed");
            }
            existing = resources;
            if (existing != null) {
                return existing;
            }
            StatefulRedisConnection<String, byte[]> connection =
                    Objects.requireNonNull(connections.get(), "Redis connection");
            try {
                ProxyManager<String> manager =
                        Bucket4jLettuce.casBasedBuilder(connection)
                                .expirationAfterWrite(
                                        ExpirationAfterWriteStrategy
                                                .basedOnTimeForRefillingBucketUpToMax(
                                                        Duration.ofSeconds(10)))
                                .requestTimeout(timeout)
                                .build();
                Resources created = new Resources(connection, manager);
                resources = created;
                return created;
            } catch (RuntimeException exception) {
                try {
                    connection.close();
                } catch (RuntimeException closeFailure) {
                    exception.addSuppressed(closeFailure);
                }
                throw exception;
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            closed = true;
            Resources existing = resources;
            resources = null;
            if (existing != null) {
                existing.connection().close();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    private record Resources(
            StatefulRedisConnection<String, byte[]> connection, ProxyManager<String> manager) {}
}
