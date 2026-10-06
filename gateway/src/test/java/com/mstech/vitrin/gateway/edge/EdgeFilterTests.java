package com.mstech.vitrin.gateway.edge;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.testing.Rules;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class EdgeFilterTests {
    private final GatewayProperties properties = Rules.gatewayProperties("CF-Connecting-IP");
    private final EdgeFilter filter =
            new EdgeFilter(new ClientAddressResolver(properties), properties);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "TrAcEpArEnT",
                "TrAcEsTaTe",
                "BaGgAgE",
                "Forwarded",
                "X-Forwarded-For",
                "X-Forwarded-Proto",
                "X-Real-IP",
                "True-Client-IP",
                "X-Vitrin-Session",
                "X-Session-Id",
                "X-User-Id",
                "X-Role",
                "X-Trace-Id",
                "X-Degraded",
                "CF-Connecting-IP",
                "CF-Ray",
                "X-B3-TraceId"
            })
    void strippedHeaderIsInvisibleThroughEveryAccessor(String name)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(name, "123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (downstream, output) -> {
                    HttpServletRequest sanitized = (HttpServletRequest) downstream;
                    assertThat(sanitized.getHeader(name)).isNull();
                    assertThat(Collections.list(sanitized.getHeaders(name))).isEmpty();
                    assertThat(Collections.list(sanitized.getHeaderNames()))
                            .noneMatch(header -> header.equalsIgnoreCase(name));
                    assertThat(sanitized.getIntHeader(name)).isEqualTo(-1);
                    assertThat(sanitized.getDateHeader(name)).isEqualTo(-1L);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"Cookie", "Origin", "X-Requested-With", "Accept"})
    void ordinaryHeadersRemainVisible(String name)
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(name, "first");
        request.addHeader(name, "second");

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (downstream, output) -> {
                    HttpServletRequest sanitized = (HttpServletRequest) downstream;
                    assertThat(sanitized.getHeader(name)).isEqualTo("first");
                    assertThat(Collections.list(sanitized.getHeaders(name)))
                            .containsExactly("first", "second");
                    assertThat(Collections.list(sanitized.getHeaderNames())).contains(name);
                });
    }

    @Test
    void customClientAddressHeaderIsStripped()
            throws jakarta.servlet.ServletException, IOException {
        GatewayProperties custom = Rules.gatewayProperties("Client-IP");
        EdgeFilter customFilter = new EdgeFilter(new ClientAddressResolver(custom), custom);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("cLiEnT-iP", "203.0.113.7");

        customFilter.doFilter(
                request,
                new MockHttpServletResponse(),
                (downstream, output) -> {
                    HttpServletRequest sanitized = (HttpServletRequest) downstream;
                    assertThat(sanitized.getHeader("Client-IP")).isNull();
                    assertThat(Collections.list(sanitized.getHeaders("Client-IP"))).isEmpty();
                    assertThat(Collections.list(sanitized.getHeaderNames()))
                            .noneMatch(name -> name.equalsIgnoreCase("Client-IP"));
                });
    }

    @Test
    void addressIsResolvedBeforeTheHeaderIsStrippedAndStartTimeIsRecorded()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        long before = System.nanoTime();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (downstream, output) -> {
                    HttpServletRequest sanitized = (HttpServletRequest) downstream;
                    assertThat(RequestAttributes.clientAddress(sanitized))
                            .isEqualTo(new ClientAddress("203.0.113.7", false));
                    long start = Objects.requireNonNull(RequestAttributes.startNanos(sanitized));
                    assertThat(start).isBetween(before, System.nanoTime());
                });
    }

    @Test
    void firstBodyWriteReplacesUpstreamCacheControl()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, output) -> {
                    HttpServletResponse wrapped = (HttpServletResponse) output;
                    wrapped.addHeader("Cache-Control", "public");
                    wrapped.addHeader("Cache-Control", "max-age=600");
                    wrapped.getWriter().write("body");
                });

        assertThat(response.getContentAsString()).isEqualTo("body");
        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
        assertEdgeHeaders(response);
    }

    @Test
    void aChainWithoutAWriteStillGetsEdgeHeaders()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertEdgeHeaders(response);
    }

    @Test
    void traceHeaderUsesTheAttributeSetByTheChain()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, output) -> {
                    request.setAttribute(RequestAttributes.TRACE_ID, "0123456789abcdef");
                    output.getWriter().write("body");
                });

        assertThat(response.getHeader("X-Trace-Id")).isEqualTo("0123456789abcdef");
    }

    @Test
    void traceHeaderIsAbsentWithoutTheAttribute()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, output) -> output.getWriter().write("body"));

        assertThat(response.getHeader("X-Trace-Id")).isNull();
    }

    @Test
    void hookDoesNotReapplyHeadersAfterTheFirstWrite()
            throws jakarta.servlet.ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (request, output) -> {
                    HttpServletResponse wrapped = (HttpServletResponse) output;
                    wrapped.getWriter().write("body");
                    wrapped.setHeader("Server-Timing", "changed;dur=1.00");
                    wrapped.setHeader("X-Content-Type-Options", "changed");
                    wrapped.getWriter().write("more");
                    wrapped.flushBuffer();
                });

        assertThat(response.getHeaders("Server-Timing")).containsExactly("changed;dur=1.00");
        assertThat(response.getHeaders("Server-Timing")).hasSize(1);
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("changed");
    }

    private static void assertEdgeHeaders(MockHttpServletResponse response) {
        assertThat(response.getHeaders("Cache-Control")).containsExactly("no-store");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Server-Timing")).contains("total;dur=");
    }
}
