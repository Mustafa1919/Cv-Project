package com.mstech.vitrin.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.testing.MutableClock;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import com.mstech.vitrin.gateway.testing.TestTokens;
import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import com.nimbusds.jose.JOSEException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AccessTokenFilterTests {
    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRES_AT = ISSUED_AT.plusSeconds(60);
    private static final String SESSION_ID = "0123456789abcdef0123456789abcdef";

    private final MutableClock clock = new MutableClock(ISSUED_AT.plusSeconds(1));
    private final FakeClient client = new FakeClient();
    private final JwksKeySource keys =
            new JwksKeySource(client, clock, Rules.gatewayProperties("CF-Connecting-IP"));
    private final AccessTokenFilter filter =
            new AccessTokenFilter(
                    new TokenVerifier("vitrin-core", "vitrin-api", clock, keys),
                    keys,
                    Problems.create());

    private @Nullable TestTokens tokens;

    @BeforeEach
    void createKeys() throws GeneralSecurityException, JOSEException {
        tokens = new TestTokens();
        client.keys = tokens().keyMap();
    }

    @Test
    void tokenlessRuleDoesNotInspectGarbageCookie() throws ServletException, IOException {
        MockHttpServletRequest request = requiredRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));
        request.setCookies(new Cookie("__Host-vitrin_at", "garbage"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(client.calls.get()).isZero();
        assertThat(RequestAttributes.token(request)).isNull();
    }

    @Test
    void missingCookieIsRejected() throws ServletException, IOException {
        MockHttpServletRequest request = requiredRequest();

        assertRejected(request, 401, "ACCESS_TOKEN_MISSING");
        assertThat(RequestAttributes.authNanos(request)).isNotNull();
    }

    @Test
    void validTokenStoresIdentityAndContinues()
            throws JOSEException, ServletException, IOException {
        MockHttpServletRequest request = withToken(validToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        VerifiedToken verified = Objects.requireNonNull(RequestAttributes.token(request));
        assertThat(verified.sessionId()).isEqualTo(SESSION_ID);
        assertThat(verified.role()).isEqualTo(Role.GUEST_COMPANY);
        assertThat(RequestAttributes.authNanos(request)).isNotNull();
        assertThat(Objects.requireNonNull(RequestAttributes.authNanos(request)))
                .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void tamperedSignatureIsRejected() throws JOSEException, ServletException, IOException {
        assertRejected(
                withToken(TestTokens.tamperSignature(validToken())), 401, "ACCESS_TOKEN_INVALID");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1})
    void tokenIsRejectedAtAndAfterExpiration(long secondsAfterExpiry)
            throws JOSEException, ServletException, IOException {
        String compact = validToken();
        clock.set(EXPIRES_AT.plusSeconds(secondsAfterExpiry));

        assertRejected(withToken(compact), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void changingRoleWithoutResigningIsRejected()
            throws JOSEException, ServletException, IOException {
        assertRejected(
                withToken(
                        TestTokens.changeRoleWithoutResigning(
                                validToken(), Role.DEMO_CANDIDATE.claimValue())),
                401,
                "ACCESS_TOKEN_INVALID");
    }

    @Test
    void unknownSigningKeyWithHealthyJwksIsUnauthorized()
            throws GeneralSecurityException, JOSEException, ServletException, IOException {
        TestTokens other = new TestTokens();
        String compact = other.access(SESSION_ID, Role.GUEST_COMPANY, ISSUED_AT, EXPIRES_AT);

        assertRejected(withToken(compact), 401, "ACCESS_TOKEN_INVALID");
        assertThat(keys.unavailable()).isFalse();
    }

    @Test
    void unsignedTokenIsRejected() throws ServletException, IOException {
        assertRejected(
                withToken(
                        TestTokens.unsigned(SESSION_ID, Role.GUEST_COMPANY, ISSUED_AT, EXPIRES_AT)),
                401,
                "ACCESS_TOKEN_INVALID");
    }

    @Test
    void refreshTypedTokenIsRejected() throws JOSEException, ServletException, IOException {
        String compact =
                tokens().signed(
                                "rt+jwt",
                                "vitrin-core",
                                "vitrin-api",
                                SESSION_ID,
                                Role.GUEST_COMPANY,
                                ISSUED_AT,
                                EXPIRES_AT);

        assertRejected(withToken(compact), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void wrongIssuerIsRejected() throws JOSEException, ServletException, IOException {
        String compact =
                tokens().signed(
                                "at+jwt",
                                "wrong-issuer",
                                "vitrin-api",
                                SESSION_ID,
                                Role.GUEST_COMPANY,
                                ISSUED_AT,
                                EXPIRES_AT);

        assertRejected(withToken(compact), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void wrongAudienceIsRejected() throws JOSEException, ServletException, IOException {
        String compact =
                tokens().signed(
                                "at+jwt",
                                "vitrin-core",
                                "wrong-audience",
                                SESSION_ID,
                                Role.GUEST_COMPANY,
                                ISSUED_AT,
                                EXPIRES_AT);

        assertRejected(withToken(compact), 401, "ACCESS_TOKEN_INVALID");
    }

    @Test
    void twoValidAccessCookiesAreRejected() throws JOSEException, ServletException, IOException {
        String compact = validToken();
        MockHttpServletRequest request = requiredRequest();
        request.setCookies(
                new Cookie("__Host-vitrin_at", compact), new Cookie("__Host-vitrin_at", compact));

        assertRejected(request, 401, "ACCESS_TOKEN_INVALID");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void failedColdJwksLoadReturnsServiceUnavailable()
            throws JOSEException, ServletException, IOException {
        client.fail = true;
        MockHttpServletResponse response =
                assertRejected(withToken(validToken()), 503, "KEYS_UNAVAILABLE");

        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void failedRefreshForUnknownKidReturnsServiceUnavailable()
            throws GeneralSecurityException, JOSEException, ServletException, IOException {
        keys.warmUp();
        client.fail = true;
        clock.advance(Duration.ofSeconds(11));
        TestTokens other = new TestTokens();
        String compact = other.access(SESSION_ID, Role.GUEST_COMPANY, ISSUED_AT, EXPIRES_AT);

        MockHttpServletResponse response =
                assertRejected(withToken(compact), 503, "KEYS_UNAVAILABLE");

        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
    }

    @Test
    void staleKnownKeyStillAuthenticatesAfterAFailedRefresh()
            throws JOSEException, ServletException, IOException {
        keys.warmUp();
        client.fail = true;
        clock.advance(Duration.ofSeconds(11));
        assertThat(keys.find("unknown")).isNull();
        assertThat(keys.unavailable()).isTrue();
        MockHttpServletRequest request = withToken(validToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(RequestAttributes.token(request)).isNotNull();
    }

    @Test
    void missingRuleFailsClosed() throws ServletException, IOException {
        assertRejected(new MockHttpServletRequest(), 500, "GATEWAY_MISCONFIGURED");
        assertThat(client.calls.get()).isZero();
    }

    private MockHttpServletResponse assertRejected(
            MockHttpServletRequest request, int status, String code)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        Problems.assertProblem(response, status, code);
        assertThat(chain.getRequest()).isNull();
        assertThat(RequestAttributes.token(request)).isNull();
        if (status != 500) {
            assertThat(RequestAttributes.authNanos(request)).isNotNull();
        }
        return response;
    }

    private String validToken() throws JOSEException {
        return tokens().access(SESSION_ID, Role.GUEST_COMPANY, ISSUED_AT, EXPIRES_AT);
    }

    private TestTokens tokens() {
        return Objects.requireNonNull(tokens);
    }

    private static MockHttpServletRequest requiredRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/test");
        request.setAttribute(
                RequestAttributes.ROUTE,
                Rules.tokenGet("/test", Role.GUEST_COMPANY, Role.DEMO_CANDIDATE));
        return request;
    }

    private static MockHttpServletRequest withToken(String compact) {
        MockHttpServletRequest request = requiredRequest();
        request.setCookies(new Cookie("__Host-vitrin_at", compact));
        return request;
    }

    private static final class FakeClient implements JwksClient {
        private final AtomicInteger calls = new AtomicInteger();
        private Map<String, ECPublicKey> keys = Map.of();
        private boolean fail;

        @Override
        public Map<String, ECPublicKey> fetch() throws IOException {
            calls.incrementAndGet();
            if (fail) {
                throw new IOException("Unavailable");
            }
            return keys;
        }
    }
}
