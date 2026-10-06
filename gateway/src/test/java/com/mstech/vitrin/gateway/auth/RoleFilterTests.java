package com.mstech.vitrin.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.testing.Problems;
import com.mstech.vitrin.gateway.testing.Rules;
import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RoleFilterTests {
    private final RoleFilter filter = new RoleFilter(Problems.create());

    @Test
    void allowedRoleContinues() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest(Role.GUEST_COMPANY);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void disallowedRoleIsForbidden() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest(Role.DEMO_CANDIDATE);

        assertRejected(request, 403, "ROLE_NOT_ALLOWED");
    }

    @Test
    void tokenlessRuleDoesNotRequireAToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.publicGet("/test"));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void tokenRuleWithoutTokenFailsClosed() throws ServletException, IOException {
        MockHttpServletRequest request = tokenRequest(Role.GUEST_COMPANY);
        request.removeAttribute(RequestAttributes.TOKEN);

        assertRejected(request, 500, "GATEWAY_MISCONFIGURED");
    }

    @Test
    void missingRuleFailsClosed() throws ServletException, IOException {
        assertRejected(new MockHttpServletRequest(), 500, "GATEWAY_MISCONFIGURED");
    }

    private void assertRejected(MockHttpServletRequest request, int status, String code)
            throws ServletException, IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        Problems.assertProblem(response, status, code);
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest tokenRequest(Role allowed) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAttributes.ROUTE, Rules.tokenGet("/test", allowed));
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        request.setAttribute(
                RequestAttributes.TOKEN,
                new VerifiedToken(
                        TokenKind.ACCESS,
                        "0123456789abcdef0123456789abcdef",
                        Role.GUEST_COMPANY,
                        issued,
                        issued.plusSeconds(60)));
        return request;
    }
}
