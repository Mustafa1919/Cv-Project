package com.mstech.vitrin.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.edge.SiteProperties;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PreflightFilterTests {
    private final PreflightFilter filter =
            new PreflightFilter(
                    new CorsPolicy(new SiteProperties("https://example.test")), Problems.create());

    @Test
    void nonPreflightContinues() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isNull();
    }

    @Test
    void allowedPreflightWithoutRequestedHeadersReturnsNoContent()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertAllowed(response);
        assertThat(chain.getRequest()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"x-requested-with", "X-Requested-With", "  x-requested-with  "})
    void allowedRequestedHeaderIsCaseInsensitiveAndTrimmed(String headers)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        request.addHeader("Access-Control-Request-Headers", headers);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertAllowed(response);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsAnUnlistedRequestedHeader() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        request.addHeader("Access-Control-Request-Headers", "X-Requested-With, Authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 403, "CORS_PREFLIGHT_REJECTED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void checksEveryRequestedHeadersHeaderLine()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        request.addHeader("Access-Control-Request-Headers", "X-Requested-With");
        request.addHeader("Access-Control-Request-Headers", "Authorization");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 403, "CORS_PREFLIGHT_REJECTED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsWrongOrigin() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        request.removeHeader("Origin");
        request.addHeader("Origin", "https://evil.test");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 403, "CORS_PREFLIGHT_REJECTED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingRuleFailsClosed() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = preflight();
        request.removeAttribute(RequestAttributes.ROUTE);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 500, "GATEWAY_MISCONFIGURED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingRuleAlsoFailsClosedForNonPreflight()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        Problems.assertProblem(response, 500, "GATEWAY_MISCONFIGURED");
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest preflight() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/test");
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicPost("/test"));
        request.setAttribute(RequestAttributes.PREFLIGHT, true);
        request.addHeader("Origin", "https://example.test");
        request.addHeader("Access-Control-Request-Method", "POST");
        return request;
    }

    private static void assertAllowed(MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("Access-Control-Allow-Methods")).isEqualTo("POST");
        assertThat(response.getHeader("Access-Control-Allow-Headers"))
                .isEqualTo("X-Requested-With");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("600");
        assertThat(response.getHeaders("Vary"))
                .containsExactly("Access-Control-Request-Method", "Access-Control-Request-Headers");
    }
}
