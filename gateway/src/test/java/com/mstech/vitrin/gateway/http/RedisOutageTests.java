package com.mstech.vitrin.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.http.GatewayHarness.FakeUpstream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisOutageTests {
    private @Nullable FakeUpstream upstream;
    private @Nullable GenericContainer<?> redis;
    private @Nullable GatewayHarness coldGateway;
    private @Nullable GatewayHarness warmGateway;

    @BeforeAll
    void startGateways() throws IOException {
        FakeUpstream fake = new FakeUpstream();
        upstream = fake;
        fake.respond(200, "{}", Map.of());
        GenericContainer<?> ownRedis = GatewayHarness.RedisSupport.separate();
        redis = ownRedis;
        coldGateway =
                GatewayHarness.builder()
                        .upstream(fake.base())
                        .redis("127.0.0.1", GatewayHarness.freePort())
                        .property("--vitrin.ratelimit.per-minute.public=3")
                        .property("--vitrin.ratelimit.redis-retry-interval=1ms")
                        .start();
        warmGateway =
                GatewayHarness.builder()
                        .upstream(fake.base())
                        .redis(ownRedis.getHost(), ownRedis.getMappedPort(6379))
                        .property("--vitrin.ratelimit.per-minute.public=3")
                        .property("--vitrin.ratelimit.redis-retry-interval=1ms")
                        .start();
    }

    @AfterAll
    void closeResources() {
        try {
            GatewayHarness cold = coldGateway;
            if (cold != null) {
                cold.close();
            }
        } finally {
            try {
                GatewayHarness warm = warmGateway;
                if (warm != null) {
                    warm.close();
                }
            } finally {
                try {
                    GenericContainer<?> container = redis;
                    if (container != null) {
                        container.stop();
                    }
                } finally {
                    FakeUpstream fake = upstream;
                    if (fake != null) {
                        fake.close();
                    }
                }
            }
        }
    }

    @Test
    void coldRedisOutageStillAnswersAndEnforcesFallbackCapacity()
            throws IOException, InterruptedException {
        assertFallbackCapacity(Objects.requireNonNull(coldGateway));
    }

    @Test
    void warmRedisOutageStillAnswersAndEnforcesFreshFallbackCapacity()
            throws IOException, InterruptedException {
        GatewayHarness running = Objects.requireNonNull(warmGateway);
        HttpResponse<String> before =
                running.send(
                        "GET",
                        "/v1/public/status",
                        Map.of("CF-Connecting-IP", GatewayHarness.nextAddress()));
        assertThat(before.statusCode()).isEqualTo(200);
        Objects.requireNonNull(redis).stop();

        assertFallbackCapacity(running);
    }

    private static void assertFallbackCapacity(GatewayHarness running)
            throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        long start = System.nanoTime();
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> response =
                    running.send("GET", "/v1/public/status", Map.of("CF-Connecting-IP", address));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("RateLimit-Remaining"))
                    .contains(Integer.toString(2 - index));
        }
        HttpResponse<String> rejected =
                running.send("GET", "/v1/public/status", Map.of("CF-Connecting-IP", address));

        running.assertProblem(rejected, 429, "RATE_LIMITED");
        assertThat(rejected.headers().firstValue("RateLimit-Limit")).contains("3");
        assertThat(rejected.headers().firstValue("RateLimit-Remaining")).contains("0");
        assertThat(Long.parseLong(rejected.headers().firstValue("Retry-After").orElseThrow()))
                .isGreaterThanOrEqualTo(1L);
        assertThat(System.nanoTime() - start).isLessThan(Duration.ofSeconds(10).toNanos());
    }
}
