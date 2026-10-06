package com.mstech.vitrin.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.edge.SiteProperties;
import com.mstech.vitrin.gateway.testing.Problems;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RouteFilterTests {
    private final RouteTable table = RouteTable.pillarZero();
    private final RouteFilter filter =
            new RouteFilter(
                    table,
                    new CorsPolicy(new SiteProperties("https://example.test")),
                    Problems.create(),
                    Problems.emptyTracers());

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/internal/../v1/public/status",
                "/v1//public/status",
                "/v1/public/status;x=1",
                "/v1/public/./status",
                "/v1/public/status/..",
                "/v1/public/%73tatus",
                "/v1/public/status%2f",
                "/v1\\public\\status",
                "/v1/public/sta tus",
                "/v1/public/statüs",
                "/v1/public/status/.",
                "/v1/public/status%",
                "/v1/public/status%41",
                "/v1/public/status\u007f"
            })
    void rejectsAmbiguousRawUriEvenWithACleanDispatchPath(String raw)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        request.setRequestURI(raw);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 400, "PATH_NOT_ALLOWED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsAmbiguousDispatchPathEvenWithACleanRawUri()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        request.setServletPath("/v1/public/./status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 400, "PATH_NOT_ALLOWED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void lookupUsesDispatchPathInsteadOfRawUri()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        request.setServletPath("/internal/jwks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 404, "ROUTE_NOT_FOUND");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void dispatchPathIncludesPathInfo() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        request.setServletPath("/v1/public");
        request.setPathInfo("/status");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(RequestAttributes.route(request))
                .isEqualTo(table.find("GET", "/v1/public/status").orElseThrow());
    }

    @ParameterizedTest
    @MethodSource("hiddenRoutes")
    void routesOutsideTheExactTableAreNotFound(String method, String path)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 404, "ROUTE_NOT_FOUND");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void knownRouteIsStoredAndContinues() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(RequestAttributes.route(request))
                .isEqualTo(table.find("GET", "/v1/public/status").orElseThrow());
    }

    @Test
    void preflightResolvesTheRequestedMethod()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight("POST");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(RequestAttributes.route(request))
                .isEqualTo(table.find("POST", "/v1/session").orElseThrow());
        assertThat(request.getAttribute(RequestAttributes.PREFLIGHT)).isEqualTo(true);
    }

    @Test
    void preflightForAnAbsentMethodIsNotFound()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight("DELETE");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 404, "ROUTE_NOT_FOUND");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void matchingOriginGetsCorsHeadersOnNotFoundResponses()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/internal/jwks");
        request.addHeader("Origin", "https://example.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Problems.assertProblem(response, 404, "ROUTE_NOT_FOUND");
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://example.test");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
    }

    @Test
    void matchingOriginGetsCorsHeadersOnAmbiguousPathResponses()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request("GET", "/v1/public/status");
        request.setRequestURI("/v1/public/%73tatus");
        request.addHeader("Origin", "https://example.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        Problems.assertProblem(response, 400, "PATH_NOT_ALLOWED");
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://example.test");
    }

    static Stream<Arguments> hiddenRoutes() {
        return Stream.of(
                Arguments.of("GET", "/internal/jwks"),
                Arguments.of("GET", "/ops/lab/enabled"),
                Arguments.of("GET", "/actuator/health"),
                Arguments.of("GET", "/"),
                Arguments.of("GET", "/v1"),
                Arguments.of("GET", "/v1/session/"),
                Arguments.of("PUT", "/v1/session"),
                Arguments.of("DELETE", "/v1/session"),
                Arguments.of("OPTIONS", "/v1/session"));
    }

    private static MockHttpServletRequest preflight(String requestedMethod) {
        MockHttpServletRequest request = request("OPTIONS", "/v1/session");
        request.addHeader("Origin", "https://example.test");
        request.addHeader("Access-Control-Request-Method", requestedMethod);
        return request;
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        request.setServletPath(path);
        return request;
    }
}
