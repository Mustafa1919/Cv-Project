package com.mstech.vitrin.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.SiteProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorsPolicyTests {
    private final CorsPolicy policy = new CorsPolicy(new SiteProperties("https://example.test"));

    @Test
    void exactOriginGetsAllCorsResponseHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://example.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        policy.decorate(request, response);

        assertThat(policy.originAllowed(request)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin"))
                .isEqualTo("https://example.test");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Expose-Headers"))
                .isEqualTo(
                        "Server-Timing, X-Trace-Id, RateLimit-Limit, RateLimit-Remaining,"
                                + " RateLimit-Reset, X-Degraded");
        assertThat(response.getHeader("Timing-Allow-Origin")).isEqualTo("https://example.test");
        assertThat(response.getHeaders("Vary")).containsExactly("Origin");
    }

    @Test
    void missingOriginStillAddsVary() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        policy.decorate(request, response);

        assertThat(policy.originAllowed(request)).isFalse();
        assertThat(response.getHeaders("Vary")).containsExactly("Origin");
        assertNoCorsHeaders(response);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://example.test.evil.test",
                "https://evilexample.test",
                "https://example.test/",
                "https://EXAMPLE.test",
                "http://example.test",
                "https://example.test:443",
                "null",
                ""
            })
    void nearMissOriginsAreNotAllowed(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", origin);
        MockHttpServletResponse response = new MockHttpServletResponse();

        policy.decorate(request, response);

        assertThat(policy.originAllowed(request)).isFalse();
        assertThat(response.getHeaders("Vary")).containsExactly("Origin");
        assertNoCorsHeaders(response);
    }

    @Test
    void rejectsTwoOriginHeadersEvenWhenOneMatches() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://example.test");
        request.addHeader("Origin", "https://evil.test");
        MockHttpServletResponse response = new MockHttpServletResponse();

        policy.decorate(request, response);

        assertThat(policy.originAllowed(request)).isFalse();
        assertNoCorsHeaders(response);
    }

    @Test
    void varyDecorationPreservesExistingValues() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Vary", "Accept-Encoding");

        policy.decorate(new MockHttpServletRequest(), response);

        assertThat(response.getHeaders("Vary")).containsExactly("Accept-Encoding", "Origin");
    }

    private static void assertNoCorsHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        assertThat(response.getHeader("Access-Control-Expose-Headers")).isNull();
        assertThat(response.getHeader("Timing-Allow-Origin")).isNull();
    }
}
