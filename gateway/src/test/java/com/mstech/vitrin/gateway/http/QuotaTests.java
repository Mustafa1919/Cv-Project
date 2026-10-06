package com.mstech.vitrin.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.http.GatewayHarness.CoreInstance;
import com.mstech.vitrin.gateway.http.GatewayHarness.Session;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuotaTests {
    private @Nullable CoreInstance core;
    private @Nullable GatewayHarness gateway;

    @BeforeAll
    void startApplications() throws IOException {
        CoreInstance instance =
                new CoreInstance(GatewayHarness.freePort(), GatewayHarness.freePort());
        core = instance;
        instance.start();
        gateway =
                GatewayHarness.builder()
                        .core(instance)
                        .property("--vitrin.ratelimit.per-minute.public=3")
                        .property("--vitrin.ratelimit.per-minute.session-create=3")
                        .property("--vitrin.ratelimit.per-minute.session-read=3")
                        .property("--vitrin.ratelimit.per-minute.authenticated=50")
                        .property("--vitrin.ratelimit.per-minute.preflight=3")
                        .start();
    }

    @AfterAll
    void closeApplications() {
        try {
            GatewayHarness running = gateway;
            if (running != null) {
                running.close();
            }
        } finally {
            CoreInstance instance = core;
            if (instance != null) {
                instance.close();
            }
        }
    }

    @Test
    void addressQuotaRejectsFourthRequestWithoutAffectingAnotherAddress()
            throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        HttpResponse<String> first = publicStatus(address, Map.of());
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("RateLimit-Limit")).contains("3");
        assertThat(first.headers().firstValue("RateLimit-Remaining")).contains("2");
        assertThat(publicStatus(address, Map.of()).statusCode()).isEqualTo(200);
        assertThat(publicStatus(address, Map.of()).statusCode()).isEqualTo(200);

        assertLimited(publicStatus(address, Map.of()), 3);
        assertThat(publicStatus(GatewayHarness.nextAddress(), Map.of()).statusCode())
                .isEqualTo(200);
    }

    @Test
    void spoofedForwardingHeadersCannotEscapeTheAddressBucket()
            throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        for (int index = 0; index < 3; index++) {
            assertThat(publicStatus(address, Map.of()).statusCode()).isEqualTo(200);
        }

        HttpResponse<String> response =
                publicStatus(
                        address,
                        Map.of(
                                "X-Forwarded-For", "8.8.8.8",
                                "Forwarded", "for=9.9.9.9",
                                "X-Real-IP", "1.1.1.1"));

        assertLimited(response, 3);
    }

    @Test
    void ipv6AddressesShareOnlyTheirOwnSixtyFourBitPrefix()
            throws IOException, InterruptedException {
        String unique = GatewayHarness.nextAddress().replace(".", "");
        String prefix =
                Integer.toHexString(Integer.parseInt(unique.substring(unique.length() - 4)));
        String first = "2001:db8:10:" + prefix + "::1";
        String second = "2001:db8:10:" + prefix + "::ffff";
        String other = "2001:db8:11:" + prefix + "::1";
        assertThat(publicStatus(first, Map.of()).statusCode()).isEqualTo(200);
        assertThat(publicStatus(first, Map.of()).statusCode()).isEqualTo(200);
        assertThat(publicStatus(second, Map.of()).statusCode()).isEqualTo(200);

        assertLimited(publicStatus(second, Map.of()), 3);
        assertThat(publicStatus(other, Map.of()).statusCode()).isEqualTo(200);
    }

    @Test
    void preflightQuotaDoesNotConsumeTheSessionCreationQuota()
            throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        Map<String, String> headers =
                Map.of(
                        "CF-Connecting-IP",
                        address,
                        "Origin",
                        GatewayHarness.ORIGIN,
                        "Access-Control-Request-Method",
                        "POST");
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> response = harness().send("OPTIONS", "/v1/session", headers);
            assertThat(response.statusCode()).isEqualTo(204);
        }

        assertLimited(harness().send("OPTIONS", "/v1/session", headers), 3);
        HttpResponse<String> created =
                harness().send("POST", "/v1/session", GatewayHarness.stateHeaders(address));
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("RateLimit-Remaining")).contains("2");
    }

    @Test
    void sessionQuotaRejectsFourthReadWithoutAffectingAnotherSession()
            throws IOException, InterruptedException {
        Session first = harness().newSession(GatewayHarness.nextAddress());
        String readAddress = GatewayHarness.nextAddress();
        for (int index = 0; index < 3; index++) {
            assertThat(read(first.access(), readAddress).statusCode()).isEqualTo(200);
        }

        assertLimited(read(first.access(), readAddress), 3);
        Session second = harness().newSession(GatewayHarness.nextAddress());
        HttpResponse<String> response = read(second.access(), GatewayHarness.nextAddress());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("RateLimit-Remaining")).contains("2");
    }

    @Test
    void failedAuthenticationNeverConsumesSessionTokens() throws IOException, InterruptedException {
        Session session = harness().newSession(GatewayHarness.nextAddress());
        String address = GatewayHarness.nextAddress();
        String invalid = GatewayHarness.tamperCookie(session.access());
        for (int index = 0; index < 10; index++) {
            harness().assertProblem(read(invalid, address), 401, "ACCESS_TOKEN_INVALID", "gateway");
        }

        HttpResponse<String> firstValid = read(session.access(), address);

        assertThat(firstValid.statusCode()).isEqualTo(200);
        assertThat(firstValid.headers().firstValue("RateLimit-Limit")).contains("3");
        assertThat(firstValid.headers().firstValue("RateLimit-Remaining")).contains("2");
    }

    @Test
    void redisContainsQuotaKeysWithoutRawAddresses() throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        Session session = harness().newSession(GatewayHarness.nextAddress());
        assertThat(publicStatus(address, Map.of()).statusCode()).isEqualTo(200);
        assertThat(read(session.access(), GatewayHarness.nextAddress()).statusCode())
                .isEqualTo(200);
        GenericContainer<?> redis = GatewayHarness.RedisSupport.shared();
        RedisClient client =
                RedisClient.create("redis://" + redis.getHost() + ":" + redis.getMappedPort(6379));
        try {
            try (StatefulRedisConnection<String, String> connection = client.connect()) {
                List<String> keys = connection.sync().keys("rl:*");
                assertThat(keys).isNotEmpty();
                assertThat(keys).anyMatch(key -> key.startsWith("rl:a:"));
                assertThat(keys).anyMatch(key -> key.startsWith("rl:s:"));
                for (String key : keys) {
                    assertThat(key).matches("rl:[as]:[a-z-]+:[0-9a-f]{32}");
                    assertThat(key).doesNotContain(address);
                }
            }
        } finally {
            client.shutdown();
        }
    }

    private HttpResponse<String> publicStatus(String address, Map<String, String> extra)
            throws IOException, InterruptedException {
        Map<String, String> headers = new HashMap<>(extra);
        headers.put("CF-Connecting-IP", address);
        return harness().send("GET", "/v1/public/status", headers);
    }

    private HttpResponse<String> read(String access, String address)
            throws IOException, InterruptedException {
        return harness()
                .send("GET", "/v1/session", Map.of("CF-Connecting-IP", address, "Cookie", access));
    }

    private void assertLimited(HttpResponse<String> response, int limit) {
        harness().assertProblem(response, 429, "RATE_LIMITED");
        assertThat(response.headers().firstValue("RateLimit-Limit"))
                .contains(Integer.toString(limit));
        assertThat(response.headers().firstValue("RateLimit-Remaining")).contains("0");
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow()))
                .isGreaterThanOrEqualTo(1L);
    }

    private GatewayHarness harness() {
        return Objects.requireNonNull(gateway);
    }
}
