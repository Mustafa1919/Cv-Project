package com.mstech.vitrin.gateway.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ServerTimingTests {
    @Test
    void metricsHaveContractOrderAndTwoDecimalPlaces() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.AUTH_NANOS, 1_250_000L);
        request.setAttribute(RequestAttributes.UPSTREAM_NANOS, 2_500_000L);
        request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());

        assertThat(ServerTiming.build(request))
                .matches("^auth;dur=1\\.25, core;dur=2\\.50, total;dur=\\d+\\.\\d{2}$");
    }

    @Test
    void omitsAbsentAuthAndCoreMetrics() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());

        assertThat(ServerTiming.build(request)).matches("^total;dur=\\d+\\.\\d{2}$");
    }

    @Test
    void presentCoreMetricDoesNotRequireAuthMetric() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.UPSTREAM_NANOS, 2_500_000L);
        request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());

        assertThat(ServerTiming.build(request))
                .matches("^core;dur=2\\.50, total;dur=\\d+\\.\\d{2}$");
    }

    @Test
    void presentAuthMetricDoesNotRequireCoreMetric() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.AUTH_NANOS, 1_250_000L);
        request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());

        assertThat(ServerTiming.build(request))
                .matches("^auth;dur=1\\.25, total;dur=\\d+\\.\\d{2}$");
    }

    @Test
    void decimalSeparatorDoesNotDependOnDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setAttribute(RequestAttributes.AUTH_NANOS, 1_250_000L);
            request.setAttribute(RequestAttributes.UPSTREAM_NANOS, 2_500_000L);
            request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());

            assertThat(ServerTiming.build(request))
                    .matches(
                            "^auth;dur=\\d+\\.\\d{2}, core;dur=\\d+\\.\\d{2},"
                                    + " total;dur=\\d+\\.\\d{2}$");
        } finally {
            Locale.setDefault(original);
        }
    }
}
