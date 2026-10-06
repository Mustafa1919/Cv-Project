package com.mstech.vitrin.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.http.GatewayHarness.FakeUpstream;
import com.mstech.vitrin.gateway.http.GatewayHarness.Header;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeBehaviourTests {
    private @Nullable FakeUpstream upstream;
    private @Nullable GatewayHarness gateway;

    @BeforeAll
    void startGateway() throws IOException {
        FakeUpstream fake = new FakeUpstream();
        upstream = fake;
        fake.respond(201, "{}", Map.of("Cache-Control", "public, max-age=60"));
        gateway = GatewayHarness.builder().upstream(fake.base()).start();
    }

    @BeforeEach
    void clearUpstreamRequests() {
        fake().clear();
    }

    @AfterAll
    void closeGateway() {
        try {
            GatewayHarness running = gateway;
            if (running != null) {
                running.close();
            }
        } finally {
            FakeUpstream fake = upstream;
            if (fake != null) {
                fake.close();
            }
        }
    }

    @Test
    void stripsUntrustedHeadersAndPropagatesTheGatewayTrace()
            throws IOException, InterruptedException {
        List<Header> headers = stateHeaders();
        headers.add(new Header("Cookie", "ordinary=value"));
        headers.add(
                new Header(
                        "traceparent", "00-11111111111111111111111111111111-2222222222222222-01"));
        for (String name :
                List.of(
                        "baggage",
                        "X-Forwarded-For",
                        "X-Forwarded-Proto",
                        "Forwarded",
                        "X-Real-IP",
                        "CF-Ray",
                        "X-Vitrin-Session",
                        "X-Session-Id",
                        "X-User-Id",
                        "X-Role",
                        "tracestate")) {
            headers.add(new Header(name, "untrusted"));
        }

        HttpResponse<String> response = harness().send("POST", "/v1/session", headers);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(fake().received()).hasSize(1);
        FakeUpstream.Received received = fake().received().getFirst();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.path()).isEqualTo("/v1/session");
        assertThat(received.headers().get("cookie")).containsExactly("ordinary=value");
        assertThat(received.headers().get("origin")).containsExactly(GatewayHarness.ORIGIN);
        assertThat(received.headers().get("x-requested-with")).containsExactly("vitrin");
        assertThat(received.headers())
                .doesNotContainKeys(
                        "baggage",
                        "x-forwarded-for",
                        "x-forwarded-proto",
                        "forwarded",
                        "x-real-ip",
                        "cf-connecting-ip",
                        "cf-ray",
                        "x-vitrin-session",
                        "x-session-id",
                        "x-user-id",
                        "x-role",
                        "tracestate");
        String trace = response.headers().firstValue("X-Trace-Id").orElseThrow();
        List<String> traceparents = Objects.requireNonNull(received.headers().get("traceparent"));
        assertThat(traceparents).hasSize(1);
        assertThat(traceparents.getFirst()).matches("00-" + trace + "-[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(trace).isNotEqualTo("11111111111111111111111111111111");
    }

    @Test
    void proxiedResponsesReceiveSingleEdgeHeaders() throws IOException, InterruptedException {
        HttpResponse<String> response = harness().send("POST", "/v1/session", stateHeaders());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("X-Trace-Id").orElseThrow())
                .matches("[0-9a-f]{32}");
        assertThat(response.headers().firstValue("Server-Timing").orElseThrow())
                .matches("core;dur=\\d+\\.\\d{2}, total;dur=\\d+\\.\\d{2}");
        assertThat(response.headers().allValues("Cache-Control")).containsExactly("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
    }

    @Test
    void exactOriginGetsCredentialedCorsAndTimingHeaders()
            throws IOException, InterruptedException {
        HttpResponse<String> response = harness().send("POST", "/v1/session", stateHeaders());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains(GatewayHarness.ORIGIN);
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials"))
                .contains("true");
        assertThat(response.headers().firstValue("Timing-Allow-Origin"))
                .contains(GatewayHarness.ORIGIN);
        List<String> exposed =
                Arrays.stream(
                                response.headers()
                                        .firstValue("Access-Control-Expose-Headers")
                                        .orElseThrow()
                                        .split(","))
                        .map(String::trim)
                        .toList();
        assertThat(exposed)
                .contains(
                        "Server-Timing",
                        "X-Trace-Id",
                        "RateLimit-Limit",
                        "RateLimit-Remaining",
                        "RateLimit-Reset",
                        "X-Degraded");
        assertThat(
                        response.headers().allValues("Vary").stream()
                                .flatMap(value -> Arrays.stream(value.split(",")))
                                .map(String::trim)
                                .toList())
                .contains("Origin");
    }

    @Test
    void otherOriginCanReadPublicStatusButGetsNoCorsPermission()
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "GET",
                                "/v1/public/status",
                                Map.of(
                                        "CF-Connecting-IP",
                                        GatewayHarness.nextAddress(),
                                        "Origin",
                                        "https://evil.test"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void allowedPreflightDoesNotRequireCookiesOrReachUpstream()
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "OPTIONS",
                                "/v1/session",
                                Map.of(
                                        "CF-Connecting-IP",
                                        GatewayHarness.nextAddress(),
                                        "Origin",
                                        GatewayHarness.ORIGIN,
                                        "Access-Control-Request-Method",
                                        "POST"));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).contains("POST");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers"))
                .contains("X-Requested-With");
        assertThat(response.headers().firstValue("Access-Control-Max-Age")).contains("600");
        assertThat(fake().received()).isEmpty();
    }

    @Test
    void wrongOriginPreflightIsForbidden() throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "OPTIONS",
                                "/v1/session",
                                Map.of(
                                        "CF-Connecting-IP", GatewayHarness.nextAddress(),
                                        "Origin", "https://evil.test",
                                        "Access-Control-Request-Method", "POST"));

        harness().assertProblem(response, 403, "CORS_PREFLIGHT_REJECTED", "gateway");
        assertThat(fake().received()).isEmpty();
    }

    @Test
    void queryStringIsRejectedBeforeProxying() throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness().send("POST", "/v1/session?q=anything", stateHeaders());

        harness().assertProblem(response, 400, "QUERY_NOT_ALLOWED");
        assertThat(fake().received()).isEmpty();
    }

    @Test
    void bodyIsRejectedBeforeProxying() throws IOException, InterruptedException {
        HttpResponse<String> response = harness().send("POST", "/v1/session", stateHeaders(), "{}");

        harness().assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(fake().received()).isEmpty();
    }

    @Test
    void upstreamReadTimeoutProducesGatewayTimeout() throws IOException, InterruptedException {
        try (FakeUpstream blocked = new FakeUpstream()) {
            try (GatewayHarness isolated =
                    GatewayHarness.builder()
                            .upstream(blocked.base())
                            .property("--spring.http.clients.read-timeout=300ms")
                            .start()) {
                blocked.clear();
                blocked.neverAnswer();

                HttpResponse<String> response =
                        isolated.send(
                                "POST",
                                "/v1/session",
                                GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));

                isolated.assertProblem(response, 504, "UPSTREAM_TIMEOUT");
                assertThat(blocked.received()).hasSize(1);
                assertThat(blocked.received().getFirst().path()).isEqualTo("/v1/session");
            }
        }
    }

    private static List<Header> stateHeaders() {
        return new ArrayList<>(
                List.of(
                        new Header("CF-Connecting-IP", GatewayHarness.nextAddress()),
                        new Header("Origin", GatewayHarness.ORIGIN),
                        new Header("X-Requested-With", "vitrin")));
    }

    private GatewayHarness harness() {
        return Objects.requireNonNull(gateway);
    }

    private FakeUpstream fake() {
        return Objects.requireNonNull(upstream);
    }
}
