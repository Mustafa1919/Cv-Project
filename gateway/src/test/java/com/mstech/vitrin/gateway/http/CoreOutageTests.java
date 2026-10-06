package com.mstech.vitrin.gateway.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.http.GatewayHarness.CoreInstance;
import com.mstech.vitrin.gateway.http.GatewayHarness.Session;
import com.mstech.vitrin.gateway.testing.TestTokens;
import com.mstech.vitrin.platform.token.Role;
import com.nimbusds.jose.JOSEException;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
class CoreOutageTests {
    private @Nullable CoreInstance core;
    private @Nullable GatewayHarness gateway;
    private @Nullable Session beforeRestart;
    private @Nullable Session afterRestart;

    @BeforeAll
    void startGatewayWhileCoreIsDown() throws IOException {
        CoreInstance instance =
                new CoreInstance(GatewayHarness.freePort(), GatewayHarness.freePort());
        core = instance;
        gateway =
                GatewayHarness.builder()
                        .core(instance)
                        .property("--vitrin.gateway.jwks-min-refresh-interval=1ms")
                        .property("--vitrin.gateway.status-cache-ttl=1ms")
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
    @Order(1)
    void unknownKeyDuringColdCoreOutageIsServiceUnavailableNotUnauthorized()
            throws GeneralSecurityException, JOSEException, IOException, InterruptedException {
        TestTokens signer = new TestTokens();
        Instant issued = Instant.now().minusSeconds(1);
        String compact =
                signer.access(
                        "0123456789abcdef0123456789abcdef",
                        Role.GUEST_COMPANY,
                        issued,
                        issued.plusSeconds(900));
        HttpResponse<String> response =
                harness()
                        .send(
                                "GET",
                                "/v1/session",
                                Map.of(
                                        "CF-Connecting-IP",
                                        GatewayHarness.nextAddress(),
                                        "Cookie",
                                        "__Host-vitrin_at=" + compact));

        harness().assertProblem(response, 503, "KEYS_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    @Order(2)
    void missingAccessCookieNeedsNoCoreKey() throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "GET",
                                "/v1/session",
                                Map.of("CF-Connecting-IP", GatewayHarness.nextAddress()));

        harness().assertProblem(response, 401, "ACCESS_TOKEN_MISSING", "gateway");
    }

    @Test
    @Order(3)
    void sessionCreationDuringCoreOutageReturnsAProblemWithTrace()
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "POST",
                                "/v1/session",
                                GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));

        harness().assertProblem(response, 503, "UPSTREAM_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
    }

    @Test
    @Order(4)
    void publicStatusDescribesOutageWithoutExposingInfrastructure()
            throws IOException, InterruptedException {
        HttpResponse<String> response = status();
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = harness().json(response);

        assertThat(body.path("status").asString()).isEqualTo("degraded");
        assertThat(body.path("components").path("core").asString()).isEqualTo("down");
        assertThat(body.path("components").path("gateway").asString()).isEqualTo("up");
        assertThat(body.path("components").path("search").asString()).isEqualTo("up");
        List<String> fields = new ArrayList<>();
        body.properties().forEach(entry -> fields.add(entry.getKey()));
        assertThat(fields).containsExactlyInAnyOrder("status", "components", "checkedAt");
        assertThat(response.body())
                .doesNotContain("localhost", Integer.toString(instance().port()), "version");
    }

    @Test
    @Order(5)
    void startingCoreEnablesLazyKeyLoadingAndHealthyStatus()
            throws IOException, InterruptedException {
        instance().start();
        harness().clock().shift(Duration.ofMillis(5));
        Session session = harness().newSession(GatewayHarness.nextAddress());
        beforeRestart = session;

        HttpResponse<String> read = read(session.access());

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(harness().json(read).path("role").asString()).isEqualTo("guest_company");
        harness().clock().shift(Duration.ofMillis(5));
        HttpResponse<String> status = status();
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(harness().json(status).path("components").path("core").asString())
                .isEqualTo("up");
        assertThat(harness().json(status).path("status").asString()).isEqualTo("ok");
    }

    @Test
    @Order(6)
    void restartingCoreReplacesCachedKeysAndRejectsTheOldTokenAtGateway()
            throws IOException, InterruptedException {
        Session old = Objects.requireNonNull(beforeRestart);
        instance().stop();
        instance().start();
        harness().clock().shift(Duration.ofMillis(5));
        Session replacement = harness().newSession(GatewayHarness.nextAddress());
        afterRestart = replacement;

        HttpResponse<String> accepted = read(replacement.access());

        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(harness().json(accepted).path("role").asString()).isEqualTo("guest_company");
        HttpResponse<String> rejected = read(old.access());
        harness().assertProblem(rejected, 401, "ACCESS_TOKEN_INVALID", "gateway");
    }

    @Test
    @Order(7)
    void knownValidTokenStillAuthenticatesWhenCoreStopsButProxyReportsOutage()
            throws IOException, InterruptedException {
        Session session = Objects.requireNonNull(afterRestart);
        instance().stop();

        HttpResponse<String> response = read(session.access());

        harness().assertProblem(response, 503, "UPSTREAM_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).contains("5");
        harness().clock().shift(Duration.ofMillis(5));
        HttpResponse<String> status = status();
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(harness().json(status).path("status").asString()).isEqualTo("degraded");
        assertThat(harness().json(status).path("components").path("core").asString())
                .isEqualTo("down");
    }

    private HttpResponse<String> read(String cookie) throws IOException, InterruptedException {
        return harness()
                .send(
                        "GET",
                        "/v1/session",
                        Map.of("CF-Connecting-IP", GatewayHarness.nextAddress(), "Cookie", cookie));
    }

    private HttpResponse<String> status() throws IOException, InterruptedException {
        return harness()
                .send(
                        "GET",
                        "/v1/public/status",
                        Map.of("CF-Connecting-IP", GatewayHarness.nextAddress()));
    }

    private GatewayHarness harness() {
        return Objects.requireNonNull(gateway);
    }

    private CoreInstance instance() {
        return Objects.requireNonNull(core);
    }
}
