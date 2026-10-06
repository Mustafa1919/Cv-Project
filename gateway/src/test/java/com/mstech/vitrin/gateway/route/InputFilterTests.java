package com.mstech.vitrin.gateway.route;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import java.io.IOException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class InputFilterTests {
    private final InputFilter filter = new InputFilter(Problems.create());

    @ParameterizedTest
    @ValueSource(strings = {"q=anything", ""})
    void rejectsAnyPresentQueryString(String query)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request();
        request.setQueryString(query);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 400, "QUERY_NOT_ALLOWED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsPositiveContentLength() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request();
        request.addHeader("Content-Length", "1");
        request.setContent(new byte[] {1});
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        assertThat(request.getContentLengthLong()).isEqualTo(1L);

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void rejectsTransferEncodingEvenWithoutPositiveContentLength()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request();
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        assertThat(request.getContentLengthLong()).isLessThanOrEqualTo(0L);

        filter.doFilter(request, response, chain);

        Problems.assertProblem(response, 400, "BODY_NOT_ALLOWED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void zeroContentLengthPasses() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request();
        request.addHeader("Content-Length", "0");
        request.setContent(new byte[0]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        assertThat(request.getContentLengthLong()).isZero();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void anExplicitlyPermissiveRuleAllowsQueryAndBody()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = request();
        request.setAttribute(
                RequestAttributes.ROUTE,
                new RouteRule(
                        "permissive",
                        "POST",
                        "/test",
                        Upstream.CORE,
                        TokenRequirement.NONE,
                        Set.of(),
                        null,
                        null,
                        true,
                        true));
        request.setQueryString("q=anything");
        request.setContent(new byte[] {1});
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void missingRuleFailsClosed() throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        Problems.assertProblem(response, 500, "GATEWAY_MISCONFIGURED");
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));
        return request;
    }
}
