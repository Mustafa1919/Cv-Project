package com.mstech.vitrin.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import com.mstech.vitrin.gateway.testing.TestSettings;
import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessionQuotaFilterTests {
    private final CountingStore store = new CountingStore();
    private final SessionQuotaFilter filter =
            new SessionQuotaFilter(
                    store, TestSettings.rates(Map.of("session-read", 2)), Problems.create());

    @Test
    void absentSessionGroupContinuesWithoutTouchingStore() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));

        Outcome outcome = invoke(request);

        assertThat(outcome.chain().getRequest()).isSameAs(request);
        assertThat(store.calls.get()).isZero();
        assertThat(outcome.response().getHeader("RateLimit-Limit")).isNull();
    }

    @Test
    void authenticatedSessionAllowsCapacityThenRejects() throws ServletException, IOException {
        Outcome first = invoke(request("first-session"));
        assertThat(first.response().getStatus()).isEqualTo(200);
        assertThat(first.response().getHeader("RateLimit-Limit")).isEqualTo("2");
        assertThat(first.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(first.response().getHeader("RateLimit-Reset")).matches("[1-9][0-9]*");
        assertThat(first.chain().getRequest()).isNotNull();
        Outcome second = invoke(request("first-session"));
        assertThat(second.response().getStatus()).isEqualTo(200);
        assertThat(second.response().getHeader("RateLimit-Remaining")).isEqualTo("0");

        Outcome rejected = invoke(request("first-session"));

        Problems.assertProblem(rejected.response(), 429, "RATE_LIMITED");
        assertThat(rejected.chain().getRequest()).isNull();
        assertThat(rejected.response().getHeader("Retry-After")).matches("[1-9][0-9]*");
        assertThat(rejected.response().getHeader("RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void anotherSessionHasAnIndependentCounter() throws ServletException, IOException {
        assertThat(invoke(request("first-session")).response().getStatus()).isEqualTo(200);
        assertThat(invoke(request("first-session")).response().getStatus()).isEqualTo(200);
        Problems.assertProblem(invoke(request("first-session")).response(), 429, "RATE_LIMITED");

        Outcome other = invoke(request("second-session"));

        assertThat(other.response().getStatus()).isEqualTo(200);
        assertThat(other.response().getHeader("RateLimit-Remaining")).isEqualTo("1");
        assertThat(other.chain().getRequest()).isNotNull();
    }

    @Test
    void missingTokenFailsClosedBeforeTheCounterMoves() throws ServletException, IOException {
        MockHttpServletRequest request = request("first-session");
        request.removeAttribute(RequestAttributes.TOKEN);

        Outcome outcome = invoke(request);

        Problems.assertProblem(outcome.response(), 500, "GATEWAY_MISCONFIGURED");
        assertThat(outcome.chain().getRequest()).isNull();
        assertThat(store.calls.get()).isZero();
    }

    @Test
    void missingRuleFailsClosedBeforeTheCounterMoves() throws ServletException, IOException {
        Outcome outcome = invoke(new MockHttpServletRequest());

        Problems.assertProblem(outcome.response(), 500, "GATEWAY_MISCONFIGURED");
        assertThat(outcome.chain().getRequest()).isNull();
        assertThat(store.calls.get()).isZero();
    }

    @Test
    void sessionHeadersReplaceAddressQuotaHeaders() throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("RateLimit-Limit", "120");
        response.addHeader("RateLimit-Remaining", "119");
        response.addHeader("RateLimit-Reset", "50");

        filter.doFilter(request("first-session"), response, new MockFilterChain());

        assertThat(response.getHeaders("RateLimit-Limit")).containsExactly("2");
        assertThat(response.getHeaders("RateLimit-Remaining")).containsExactly("1");
        assertThat(response.getHeaders("RateLimit-Reset")).hasSize(1);
    }

    private Outcome invoke(MockHttpServletRequest request) throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain);
    }

    private static MockHttpServletRequest request(String sessionId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.tokenGet("/test", Role.GUEST_COMPANY));
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        request.setAttribute(
                RequestAttributes.TOKEN,
                new VerifiedToken(
                        TokenKind.ACCESS,
                        sessionId,
                        Role.GUEST_COMPANY,
                        issued,
                        issued.plusSeconds(60)));
        return request;
    }

    private record Outcome(MockHttpServletResponse response, MockFilterChain chain) {}

    private static final class CountingStore implements QuotaStore {
        private final AtomicInteger calls = new AtomicInteger();
        private final MemoryQuotaStore delegate = new MemoryQuotaStore(100);

        @Override
        public QuotaDecision tryConsume(String key, int limitPerMinute) {
            calls.incrementAndGet();
            return delegate.tryConsume(key, limitPerMinute);
        }
    }
}
