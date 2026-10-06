package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.ClientAddress;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.TokenRequirement;
import com.mstech.vitrin.gateway.route.Upstream;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.TestSettings;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AddressQuotaFilterTests {
    private final AddressQuotaFilter filter =
            new AddressQuotaFilter(
                    new MemoryQuotaStore(100),
                    TestSettings.rates(Map.of("public", 2, "session-create", 2, "preflight", 1)),
                    AddressHasher.load(null, false),
                    Problems.create());

    @Test
    void successfulConsumptionSetsAllRateLimitHeaders() throws ServletException, IOException {
        Outcome outcome = invoke(request("public", "203.0.113.7"));

        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.chain().getRequest()).isNotNull();
        assertThat(outcome.response().getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(outcome.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(outcome.response().getHeader("RateLimit-Reset")).matches("[1-9][0-9]*");
    }

    @Test
    void requestBeyondCapacityIsRejectedWithRetryAfter() throws ServletException, IOException {
        assertThat(invoke(request("public", "203.0.113.7")).response().getStatus()).isEqualTo(200);
        assertThat(invoke(request("public", "203.0.113.7")).response().getStatus()).isEqualTo(200);

        Outcome outcome = invoke(request("public", "203.0.113.7"));

        Problems.assertProblem(outcome.response(), 429, "RATE_LIMITED");
        assertThat(outcome.chain().getRequest()).isNull();
        assertThat(outcome.response().getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(outcome.response().getHeader("RateLimit-Remaining")).isEqualTo("0");
        assertThat(outcome.response().getHeader("RateLimit-Reset")).matches("[1-9][0-9]*");
        assertThat(outcome.response().getHeader("Retry-After")).matches("[1-9][0-9]*");
    }

    @Test
    void anotherAddressHasAnIndependentCounter() throws ServletException, IOException {
        exhaustPublicAddress();

        Outcome outcome = invoke(request("public", "203.0.113.8"));

        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(outcome.chain().getRequest()).isNotNull();
    }

    @Test
    void anotherGroupHasAnIndependentCounter() throws ServletException, IOException {
        exhaustPublicAddress();

        Outcome outcome = invoke(request("session-create", "203.0.113.7"));

        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(outcome.chain().getRequest()).isNotNull();
    }

    @Test
    void preflightUsesItsOwnGroupWithoutConsumingTheRouteGroup()
            throws ServletException, IOException {
        MockHttpServletRequest first = request("public", "203.0.113.7");
        first.setAttribute(RequestAttributes.PREFLIGHT, true);
        Outcome accepted = invoke(first);
        assertThat(accepted.response().getStatus()).isEqualTo(200);
        assertThat(accepted.response().getHeader("RateLimit-Limit")).isEqualTo("1");
        assertThat(accepted.response().getHeader("RateLimit-Remaining")).isEqualTo("0");

        MockHttpServletRequest second = request("public", "203.0.113.7");
        second.setAttribute(RequestAttributes.PREFLIGHT, true);
        Outcome rejected = invoke(second);
        Problems.assertProblem(rejected.response(), 429, "RATE_LIMITED");
        assertThat(rejected.chain().getRequest()).isNull();

        Outcome ordinary = invoke(request("public", "203.0.113.7"));
        assertThat(ordinary.response().getStatus()).isEqualTo(200);
        assertThat(ordinary.response().getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(ordinary.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    void absentGroupContinuesWithoutRateLimitHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                RequestAttributes.ROUTE,
                new RouteRule(
                        "unlimited",
                        "GET",
                        "/test",
                        Upstream.LOCAL,
                        TokenRequirement.NONE,
                        Set.of(),
                        null,
                        null,
                        false,
                        false));

        Outcome outcome = invoke(request);

        assertThat(outcome.response().getStatus()).isEqualTo(200);
        assertThat(outcome.chain().getRequest()).isSameAs(request);
        assertThat(outcome.response().getHeader("RateLimit-Limit")).isNull();
        assertThat(outcome.response().getHeader("RateLimit-Remaining")).isNull();
        assertThat(outcome.response().getHeader("RateLimit-Reset")).isNull();
    }

    @Test
    void missingClientAddressFailsClosed() throws ServletException, IOException {
        MockHttpServletRequest request = request("public", "203.0.113.7");
        request.removeAttribute(RequestAttributes.CLIENT_ADDRESS);

        Outcome outcome = invoke(request);

        Problems.assertProblem(outcome.response(), 500, "GATEWAY_MISCONFIGURED");
        assertThat(outcome.chain().getRequest()).isNull();
    }

    @Test
    void missingRuleFailsClosed() throws ServletException, IOException {
        Outcome outcome = invoke(new MockHttpServletRequest());

        Problems.assertProblem(outcome.response(), 500, "GATEWAY_MISCONFIGURED");
        assertThat(outcome.chain().getRequest()).isNull();
    }

    private void exhaustPublicAddress() throws ServletException, IOException {
        assertThat(invoke(request("public", "203.0.113.7")).response().getStatus()).isEqualTo(200);
        assertThat(invoke(request("public", "203.0.113.7")).response().getStatus()).isEqualTo(200);
        Problems.assertProblem(
                invoke(request("public", "203.0.113.7")).response(), 429, "RATE_LIMITED");
    }

    private Outcome invoke(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private static MockHttpServletRequest request(String group, String address) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                RequestAttributes.ROUTE,
                new RouteRule(
                        "limited",
                        "GET",
                        "/test",
                        Upstream.LOCAL,
                        TokenRequirement.NONE,
                        Set.of(),
                        group,
                        null,
                        false,
                        false));
        request.setAttribute(RequestAttributes.CLIENT_ADDRESS, new ClientAddress(address, false));
        return request;
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}
}
