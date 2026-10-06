package com.mstech.vitrin.gateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;
import tools.jackson.databind.json.JsonMapper;

class UpstreamFilterTests {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    private final UpstreamFilter filter = new UpstreamFilter(Problems.create());

    @Test
    void successPassesThroughTheResponseAndRecordsDuration() throws Exception {
        MockHttpServletRequest request = coreRequest();
        ServerResponse expected = ServerResponse.status(201).body("upstream");
        AtomicInteger calls = new AtomicInteger();

        ServerResponse actual =
                filter.filter(
                        serverRequest(request),
                        downstream -> {
                            calls.incrementAndGet();
                            return expected;
                        });

        assertThat(actual).isSameAs(expected);
        assertThat(actual.statusCode().value()).isEqualTo(201);
        assertThat(calls.get()).isEqualTo(1);
        assertDurationRecorded(request);
    }

    @Test
    void connectionFailureReturnsServiceUnavailableWithRetryAfter() throws Exception {
        MockHttpServletRequest request = coreRequest();

        ServerResponse response =
                filter.filter(
                        serverRequest(request),
                        downstream -> {
                            throw new ResourceAccessException(
                                    "Connection failed", new ConnectException("Refused"));
                        });

        MockHttpServletResponse rendered =
                assertProblem(request, response, 503, "UPSTREAM_UNAVAILABLE");
        assertThat(rendered.getHeader("Retry-After")).isEqualTo("5");
        assertDurationRecorded(request);
    }

    @ParameterizedTest
    @MethodSource("timeoutCauses")
    void timeoutAnywhereInTheCauseChainReturnsGatewayTimeout(IOException cause) throws Exception {
        MockHttpServletRequest request = coreRequest();

        ServerResponse response =
                filter.filter(
                        serverRequest(request),
                        downstream -> {
                            throw new ResourceAccessException("Request timed out", cause);
                        });

        MockHttpServletResponse rendered =
                assertProblem(request, response, 504, "UPSTREAM_TIMEOUT");
        assertThat(rendered.getHeader("Retry-After")).isNull();
        assertDurationRecorded(request);
    }

    @Test
    void otherRestClientFailuresReturnBadGateway() throws Exception {
        MockHttpServletRequest request = coreRequest();

        ServerResponse response =
                filter.filter(
                        serverRequest(request),
                        downstream -> {
                            throw new RestClientException("Upstream response failed");
                        });

        MockHttpServletResponse rendered = assertProblem(request, response, 502, "UPSTREAM_ERROR");
        assertThat(rendered.getHeader("Retry-After")).isNull();
        assertDurationRecorded(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "local"})
    void invalidRouteStateRejectsWithoutInvokingTheHandler(String scenario) throws Exception {
        MockHttpServletRequest request = coreRequest();
        if ("missing".equals(scenario)) {
            request.removeAttribute(RequestAttributes.ROUTE);
        } else {
            request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));
        }
        AtomicInteger calls = new AtomicInteger();

        ServerResponse response =
                filter.filter(
                        serverRequest(request),
                        downstream -> {
                            calls.incrementAndGet();
                            return ServerResponse.ok().build();
                        });

        MockHttpServletResponse rendered =
                assertProblem(request, response, 500, "GATEWAY_MISCONFIGURED");
        assertThat(rendered.getHeader("Retry-After")).isNull();
        assertThat(calls.get()).isZero();
        assertThat(RequestAttributes.upstreamNanos(request)).isNull();
    }

    static Stream<Arguments> timeoutCauses() {
        return Stream.of(
                        new HttpConnectTimeoutException("Connect timeout"),
                        new HttpTimeoutException("Request timeout"),
                        new SocketTimeoutException("Socket timeout"))
                .flatMap(
                        timeout ->
                                Stream.of(
                                        Arguments.of(timeout),
                                        Arguments.of(
                                                new IOException(
                                                        "Outer failure",
                                                        new IOException(
                                                                "Inner failure", timeout)))));
    }

    private static MockHttpServletResponse assertProblem(
            MockHttpServletRequest request, ServerResponse response, int status, String code)
            throws ServletException, IOException {
        assertThat(response.statusCode().value()).isEqualTo(status);
        MockHttpServletResponse rendered = new MockHttpServletResponse();

        response.writeTo(request, rendered, responseContext());

        Problems.assertProblem(rendered, status, code);
        assertThat(Problems.body(rendered).path("traceId").asString()).isEqualTo(TRACE_ID);
        assertThat(rendered.getHeader("Cache-Control")).isEqualTo("no-store");
        return rendered;
    }

    private static ServerResponse.Context responseContext() {
        JsonMapper mapper =
                JsonMapper.builder()
                        .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                        .build();
        List<HttpMessageConverter<?>> converters =
                List.of(new JacksonJsonHttpMessageConverter(mapper));
        return new ServerResponse.Context() {
            @Override
            public List<HttpMessageConverter<?>> messageConverters() {
                return converters;
            }
        };
    }

    private static void assertDurationRecorded(MockHttpServletRequest request) {
        Long duration = RequestAttributes.upstreamNanos(request);
        assertThat(duration).isNotNull();
        assertThat(Objects.requireNonNull(duration)).isGreaterThanOrEqualTo(0L);
    }

    private static ServerRequest serverRequest(MockHttpServletRequest request) {
        return ServerRequest.create(request, List.of());
    }

    private static MockHttpServletRequest coreRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/test");
        request.setServletPath("/test");
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicPost("/test"));
        request.setAttribute(RequestAttributes.TRACE_ID, TRACE_ID);
        return request;
    }
}
