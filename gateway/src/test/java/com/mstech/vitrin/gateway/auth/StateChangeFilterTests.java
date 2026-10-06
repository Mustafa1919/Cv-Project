package com.mstech.vitrin.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.edge.SiteProperties;
import com.mstech.vitrin.gateway.route.CorsPolicy;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.TokenRequirement;
import com.mstech.vitrin.gateway.route.Upstream;
import com.mstech.vitrin.gateway.testing.Problems;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class StateChangeFilterTests {
    private final StateChangeFilter filter =
            new StateChangeFilter(
                    new CorsPolicy(new SiteProperties("https://example.test")), Problems.create());

    @Test
    void getDoesNotRequireStateChangeHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = request("GET");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void validHeadersAllowEveryStateChangingMethod(String method)
            throws ServletException, IOException {
        MockHttpServletRequest request = validRequest(method);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @ParameterizedTest
    @MethodSource("rejectedHeaders")
    void rejectsInvalidHeadersForEveryStateChangingMethod(
            String method, String scenario, String code) throws ServletException, IOException {
        MockHttpServletRequest request = validRequest(method);
        switch (scenario) {
            case "missing-origin" -> request.removeHeader("Origin");
            case "wrong-origin" -> {
                request.removeHeader("Origin");
                request.addHeader("Origin", "https://evil.test");
            }
            case "two-origins" -> request.addHeader("Origin", "https://example.test");
            case "missing-requested-with" -> request.removeHeader("X-Requested-With");
            case "Vitrin", "vitrin ", "XMLHttpRequest" -> {
                request.removeHeader("X-Requested-With");
                request.addHeader("X-Requested-With", scenario);
            }
            case "two-requested-with" -> request.addHeader("X-Requested-With", "vitrin");
            case "both-wrong" -> {
                request.removeHeader("Origin");
                request.addHeader("Origin", "https://evil.test");
                request.removeHeader("X-Requested-With");
                request.addHeader("X-Requested-With", "wrong");
            }
            default -> throw new IllegalArgumentException("Unknown test scenario");
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 403, code);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingRuleFailsClosed() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        Problems.assertProblem(response, 500, "GATEWAY_MISCONFIGURED");
        assertThat(chain.getRequest()).isNull();
    }

    static Stream<Arguments> rejectedHeaders() {
        return Stream.of("POST", "PUT", "PATCH", "DELETE")
                .flatMap(
                        method ->
                                Stream.of(
                                        Arguments.of(
                                                method, "missing-origin", "ORIGIN_NOT_ALLOWED"),
                                        Arguments.of(method, "wrong-origin", "ORIGIN_NOT_ALLOWED"),
                                        Arguments.of(method, "two-origins", "ORIGIN_NOT_ALLOWED"),
                                        Arguments.of(
                                                method,
                                                "missing-requested-with",
                                                "REQUESTED_WITH_REQUIRED"),
                                        Arguments.of(method, "Vitrin", "REQUESTED_WITH_REQUIRED"),
                                        Arguments.of(method, "vitrin ", "REQUESTED_WITH_REQUIRED"),
                                        Arguments.of(
                                                method,
                                                "XMLHttpRequest",
                                                "REQUESTED_WITH_REQUIRED"),
                                        Arguments.of(
                                                method,
                                                "two-requested-with",
                                                "REQUESTED_WITH_REQUIRED"),
                                        Arguments.of(method, "both-wrong", "ORIGIN_NOT_ALLOWED")));
    }

    private static MockHttpServletRequest validRequest(String method) {
        MockHttpServletRequest request = request(method);
        request.addHeader("Origin", "https://example.test");
        request.addHeader("X-Requested-With", "vitrin");
        return request;
    }

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/test");
        request.setAttribute(
                RequestAttributes.ROUTE,
                new RouteRule(
                        "test",
                        method,
                        "/test",
                        Upstream.CORE,
                        TokenRequirement.NONE,
                        Set.of(),
                        null,
                        null,
                        false,
                        false));
        return request;
    }
}
