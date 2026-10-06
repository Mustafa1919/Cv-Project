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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationMatrixTests {
    private @Nullable CoreInstance core;
    private @Nullable GatewayHarness gateway;

    @BeforeAll
    void startApplications() throws IOException {
        CoreInstance instance =
                new CoreInstance(GatewayHarness.freePort(), GatewayHarness.freePort());
        core = instance;
        instance.start();
        gateway = GatewayHarness.builder().core(instance).start();
    }

    @AfterEach
    void resetClock() {
        GatewayHarness running = gateway;
        if (running != null) {
            running.clock().reset();
        }
    }

    @AfterAll
    void closeApplications() {
        GatewayHarness running = gateway;
        try {
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

    @ParameterizedTest
    @ValueSource(strings = {"none", "tampered", "expired", "changed-role", "valid"})
    void publicStatusIgnoresAccessToken(String column) throws IOException, InterruptedException {
        Map<String, String> headers = accessHeaders(column, false);

        HttpResponse<String> response = harness().send("GET", "/v1/public/status", headers);

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "tampered", "expired", "changed-role", "valid"})
    void sessionCreationDelegatesTokenHandlingToCore(String column)
            throws IOException, InterruptedException, GeneralSecurityException, JOSEException {
        Map<String, String> headers =
                new HashMap<>(GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));
        if ("expired".equals(column)) {
            // Core has its own system clock; shifting the gateway cannot expire a core token.
            TestTokens signer = new TestTokens();
            Instant expiredAt = Instant.now().minusSeconds(60);
            headers.put(
                    "Cookie",
                    "__Host-vitrin_at="
                            + signer.access(
                                    "0123456789abcdef0123456789abcdef",
                                    Role.GUEST_COMPANY,
                                    expiredAt.minusSeconds(900),
                                    expiredAt));
        } else {
            headers.putAll(accessHeaders(column, false));
        }

        HttpResponse<String> response = harness().send("POST", "/v1/session", headers);

        int expected = "valid".equals(column) ? 200 : 201;
        assertThat(response.statusCode()).isEqualTo(expected);
        if (expected == 201) {
            assertThat(response.headers().allValues("Set-Cookie")).isNotEmpty();
            assertThat(GatewayHarness.accessCookie(response)).startsWith("__Host-vitrin_at=");
            assertThat(GatewayHarness.refreshCookie(response)).startsWith("__Secure-vitrin_rt=");
        } else {
            assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "tampered", "expired", "changed-role", "valid"})
    void sessionReadRequiresAValidAccessToken(String column)
            throws IOException, InterruptedException {
        Map<String, String> headers = accessHeaders(column, false);

        HttpResponse<String> response = harness().send("GET", "/v1/session", headers);

        if ("valid".equals(column)) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(harness().json(response).path("role").asString()).isEqualTo("guest_company");
        } else {
            harness()
                    .assertProblem(
                            response,
                            401,
                            "none".equals(column) ? "ACCESS_TOKEN_MISSING" : "ACCESS_TOKEN_INVALID",
                            "gateway");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "tampered", "access-in-refresh", "valid"})
    void refreshAuthenticationIsDecidedByCore(String column)
            throws IOException, InterruptedException {
        Session session = harness().newSession(GatewayHarness.nextAddress());
        Map<String, String> headers =
                new HashMap<>(GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));
        switch (column) {
            case "none" -> {}
            case "tampered" ->
                    headers.put("Cookie", GatewayHarness.tamperCookie(session.refresh()));
            case "access-in-refresh" ->
                    headers.put(
                            "Cookie",
                            "__Secure-vitrin_rt=" + GatewayHarness.cookieValue(session.access()));
            case "valid" -> headers.put("Cookie", session.refresh());
            default -> throw new IllegalArgumentException("Unknown column");
        }

        HttpResponse<String> response = harness().send("POST", "/v1/session/refresh", headers);

        if ("valid".equals(column)) {
            assertThat(response.statusCode()).isEqualTo(200);
            String access = GatewayHarness.accessCookie(response);
            HttpResponse<String> read =
                    harness()
                            .send(
                                    "GET",
                                    "/v1/session",
                                    Map.of(
                                            "CF-Connecting-IP",
                                            GatewayHarness.nextAddress(),
                                            "Cookie",
                                            access));
            assertThat(read.statusCode()).isEqualTo(200);
            assertThat(harness().json(read).path("role").asString()).isEqualTo("guest_company");
        } else {
            harness()
                    .assertProblem(
                            response,
                            401,
                            "none".equals(column)
                                    ? "REFRESH_TOKEN_MISSING"
                                    : "REFRESH_TOKEN_INVALID",
                            "core");
        }
    }

    @ParameterizedTest
    @MethodSource("hiddenCells")
    void hiddenEndpointsNeverExistOutsideTheGateway(String method, String path, String column)
            throws IOException, InterruptedException {
        Map<String, String> headers = accessHeaders(column, true);

        HttpResponse<String> response = harness().send(method, path, headers);

        harness().assertProblem(response, 404, "ROUTE_NOT_FOUND");
    }

    @ParameterizedTest
    @MethodSource("stateChangeCells")
    void invalidStateChangeHeadersAreRejectedBeforeCore(String path, String scenario, String code)
            throws IOException, InterruptedException {
        Map<String, String> headers =
                new HashMap<>(GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));
        switch (scenario) {
            case "missing-origin" -> headers.remove("Origin");
            case "wrong-origin" -> headers.put("Origin", "https://evil.test");
            case "missing-requested-with" -> headers.remove("X-Requested-With");
            default -> throw new IllegalArgumentException("Unknown scenario");
        }

        HttpResponse<String> response = harness().send("POST", path, headers);

        harness().assertProblem(response, 403, code, "gateway");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal/../v1/session",
                "/v1/public/../session",
                "/v1/session;a=b",
                "/v1//session",
                "/v1/%73ession"
            })
    void confusedPathsAreRejected(String path) throws IOException, InterruptedException {
        HttpResponse<String> response =
                harness()
                        .send(
                                "POST",
                                path,
                                GatewayHarness.stateHeaders(GatewayHarness.nextAddress()));

        harness().assertProblem(response, 400, "PATH_NOT_ALLOWED");
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    static Stream<Arguments> hiddenCells() {
        return Stream.of(
                        "/internal/jwks",
                        "/internal/anything",
                        "/ops/lab/enabled",
                        "/actuator/health")
                .flatMap(
                        path ->
                                Stream.of("GET", "POST")
                                        .flatMap(
                                                method ->
                                                        Stream.of(
                                                                        "none",
                                                                        "tampered",
                                                                        "expired",
                                                                        "changed-role",
                                                                        "valid")
                                                                .map(
                                                                        column ->
                                                                                Arguments.of(
                                                                                        method,
                                                                                        path,
                                                                                        column))));
    }

    static Stream<Arguments> stateChangeCells() {
        return Stream.of("/v1/session", "/v1/session/refresh")
                .flatMap(
                        path ->
                                Stream.of(
                                        Arguments.of(path, "missing-origin", "ORIGIN_NOT_ALLOWED"),
                                        Arguments.of(path, "wrong-origin", "ORIGIN_NOT_ALLOWED"),
                                        Arguments.of(
                                                path,
                                                "missing-requested-with",
                                                "REQUESTED_WITH_REQUIRED")));
    }

    private Map<String, String> accessHeaders(String column, boolean stateHeaders)
            throws IOException, InterruptedException {
        String address = GatewayHarness.nextAddress();
        Map<String, String> headers =
                new HashMap<>(
                        stateHeaders
                                ? GatewayHarness.stateHeaders(address)
                                : Map.of("CF-Connecting-IP", address));
        if ("none".equals(column)) {
            return headers;
        }
        Session session = harness().newSession(GatewayHarness.nextAddress());
        String cookie = session.access();
        switch (column) {
            case "valid" -> {}
            case "tampered" -> cookie = GatewayHarness.tamperCookie(cookie);
            case "changed-role" -> cookie = GatewayHarness.changeRoleCookie(cookie);
            case "expired" -> harness().clock().shift(Duration.ofMinutes(16));
            default -> throw new IllegalArgumentException("Unknown column");
        }
        headers.put("Cookie", cookie);
        return headers;
    }

    private GatewayHarness harness() {
        return Objects.requireNonNull(gateway);
    }
}
