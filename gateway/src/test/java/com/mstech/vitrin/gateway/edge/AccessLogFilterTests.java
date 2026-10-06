package com.mstech.vitrin.gateway.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mstech.vitrin.gateway.testing.Rules;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessLogFilterTests {

    private final AccessLogFilter filter = new AccessLogFilter();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private @Nullable Level previousLevel;

    @BeforeEach
    void attachAppender() {
        previousLevel = logger.getLevel();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    @Test
    void logsMatchedRoute() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (chainRequest, chainResponse) -> {
                    chainRequest.setAttribute(
                            RequestAttributes.ROUTE, Rules.publicPost("/v1/session"));
                    response.setStatus(201);
                });

        assertEvent("POST", "public-post", 201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
    void preservesAcceptedMethods(String method) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(204));

        assertEvent(method, "unmatched", 204);
    }

    @Test
    void logsUnmatchedRoute() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(404));

        assertEvent("GET", "unmatched", 404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PROPFIND", "UNKNOWN\nMETHOD"})
    void replacesUnknownMethodsWithoutLoggingTheirText(String method)
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(405));

        ILoggingEvent event = assertEvent("_OTHER", "unmatched", 405);
        assertNoText(event, method);
    }

    @Test
    void logsFailureAndPropagatesTheSameException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("sensitive-failure-message");

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (chainRequest, chainResponse) -> {
                                            response.setStatus(201);
                                            throw failure;
                                        }))
                .isSameAs(failure);

        ILoggingEvent event = assertEvent("POST", "unmatched", 500);
        assertThat(event.getThrowableProxy()).isNull();
        assertNoText(event, "sensitive-failure-message");
    }

    @Test
    void preservesCommittedStatusWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ServletException failure = new ServletException("upstream-failure");

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (chainRequest, chainResponse) -> {
                                            response.setStatus(502);
                                            response.flushBuffer();
                                            throw failure;
                                        }))
                .isSameAs(failure);

        assertThat(response.isCommitted()).isTrue();
        assertEvent("GET", "unmatched", 502);
    }

    @Test
    void measuresDurationFromTheEdgeStartTime() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setAttribute(
                RequestAttributes.START_NANOS, Long.valueOf(System.nanoTime() - 250_000_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(200));

        ILoggingEvent event = assertEvent("GET", "unmatched", 200);
        assertThat(durationMillis(event)).isGreaterThanOrEqualTo(250L);
    }

    @Test
    void measuresDurationFromEntryWhenTheEdgeStartTimeIsAbsent()
            throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(200));

        ILoggingEvent event = assertEvent("GET", "unmatched", 200);
        assertThat(durationMillis(event)).isGreaterThanOrEqualTo(0L).isLessThan(10_000L);
    }

    @Test
    void clampsFutureStartTimeToZeroDuration() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        request.setAttribute(
                RequestAttributes.START_NANOS, Long.valueOf(System.nanoTime() + 60_000_000_000L));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request, response, (chainRequest, chainResponse) -> response.setStatus(200));

        ILoggingEvent event = assertEvent("GET", "unmatched", 200);
        assertThat(durationMillis(event)).isZero();
    }

    @Test
    void excludesSensitiveRequestData() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/v1/session/secret-path");
        request.setQueryString("token=abc123");
        request.addHeader("Cookie", "__Host-vitrin_at=cookie-value");
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (chainRequest, chainResponse) -> {
                    chainRequest.setAttribute(
                            RequestAttributes.ROUTE, Rules.publicPost("/v1/session/secret-path"));
                    response.setStatus(201);
                });

        ILoggingEvent event = assertEvent("POST", "public-post", 201);
        assertNoText(event, "secret-path");
        assertNoText(event, "abc123");
        assertNoText(event, "cookie-value");
        assertNoText(event, "203.0.113.9");
        assertThat(event.getArgumentArray()).isNull();
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    void disabledInfoLoggingStillRunsTheChain() throws ServletException, IOException {
        logger.setLevel(Level.WARN);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainRan = new AtomicBoolean();

        filter.doFilter(
                request,
                response,
                (chainRequest, chainResponse) -> {
                    chainRan.set(true);
                    response.setStatus(204);
                });

        assertThat(chainRan.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void isOrderedAfterTracingAndBeforeRouting() {
        Order order = AccessLogFilter.class.getAnnotation(Order.class);

        assertThat(order).isNotNull();
        assertThat(Objects.requireNonNull(order).value()).isEqualTo(FilterOrder.ACCESS_LOG);
        assertThat(FilterOrder.ACCESS_LOG)
                .isGreaterThan(Ordered.HIGHEST_PRECEDENCE + 1)
                .isLessThan(FilterOrder.ROUTE);
    }

    private ILoggingEvent assertEvent(String method, String route, int status) {
        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getMessage()).isEqualTo("request");
        assertThat(event.getFormattedMessage()).isEqualTo("request");
        List<KeyValuePair> pairs = keyValuePairs(event);
        assertThat(pairs)
                .extracting(pair -> pair.key)
                .containsExactly(
                        "http.request.method",
                        "http.route",
                        "http.response.status_code",
                        "duration_ms");
        assertThat(pairs.get(0).value).isEqualTo(method);
        assertThat(pairs.get(1).value).isEqualTo(route);
        assertThat(pairs.get(2).value)
                .isInstanceOf(Integer.class)
                .isEqualTo(Integer.valueOf(status));
        assertThat(pairs.get(3).value).isInstanceOf(Long.class);
        assertThat(durationMillis(event)).isGreaterThanOrEqualTo(0L);
        return event;
    }

    private static List<KeyValuePair> keyValuePairs(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        assertThat(pairs).isNotNull();
        return Objects.requireNonNull(pairs);
    }

    private static long durationMillis(ILoggingEvent event) {
        Object value = keyValuePairs(event).get(3).value;
        assertThat(value).isInstanceOf(Long.class);
        return ((Long) Objects.requireNonNull(value)).longValue();
    }

    private static void assertNoText(ILoggingEvent event, String text) {
        assertThat(event.getFormattedMessage()).doesNotContain(text);
        for (KeyValuePair pair : keyValuePairs(event)) {
            assertThat(pair.key).doesNotContain(text);
            assertThat(String.valueOf(pair.value)).doesNotContain(text);
        }
    }
}
