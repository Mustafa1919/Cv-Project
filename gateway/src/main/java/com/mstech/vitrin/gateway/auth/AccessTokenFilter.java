package com.mstech.vitrin.gateway.auth;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.TokenRequirement;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.TokenRejectedException;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.TOKEN)
public final class AccessTokenFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccessTokenFilter.class);
    private static final String COOKIE_NAME = "__Host-vitrin_at";

    private final TokenVerifier verifier;
    private final JwksKeySource keySource;
    private final GatewayProblems problems;

    public AccessTokenFilter(
            TokenVerifier verifier, JwksKeySource keySource, GatewayProblems problems) {
        this.verifier = verifier;
        this.keySource = keySource;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteRule rule = RequestAttributes.route(request);
        if (rule == null) {
            problems.send(
                    request, response, HttpStatus.INTERNAL_SERVER_ERROR, "GATEWAY_MISCONFIGURED");
            return;
        }
        if (rule.token() != TokenRequirement.REQUIRED) {
            chain.doFilter(request, response);
            return;
        }

        long start = System.nanoTime();
        request.removeAttribute(RequestAttributes.TOKEN);
        CookieSelection selection = selectCookie(request);
        if (selection.count() == 0) {
            recordDuration(request, start);
            LOGGER.debug("Access token missing");
            problems.send(request, response, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_MISSING");
            return;
        }
        if (selection.count() != 1) {
            recordDuration(request, start);
            LOGGER.debug("Access token cookie sent more than once");
            problems.send(request, response, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID");
            return;
        }

        final VerifiedToken token;
        try {
            token = verifier.verify(selection.value(), TokenKind.ACCESS);
        } catch (TokenRejectedException exception) {
            recordDuration(request, start);
            LOGGER.debug("Access token rejected: {}", exception.reason());
            if (exception.reason() == TokenRejectedException.Reason.UNKNOWN_KEY
                    && keySource.unavailable()) {
                response.setHeader("Retry-After", "5");
                problems.send(
                        request, response, HttpStatus.SERVICE_UNAVAILABLE, "KEYS_UNAVAILABLE");
            } else {
                problems.send(request, response, HttpStatus.UNAUTHORIZED, "ACCESS_TOKEN_INVALID");
            }
            return;
        }
        recordDuration(request, start);
        request.setAttribute(RequestAttributes.TOKEN, token);
        chain.doFilter(request, response);
    }

    private static CookieSelection selectCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return new CookieSelection(0, null);
        }
        int count = 0;
        String value = null;
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                count++;
                value = cookie.getValue();
            }
        }
        return new CookieSelection(count, value);
    }

    private static void recordDuration(HttpServletRequest request, long start) {
        request.setAttribute(RequestAttributes.AUTH_NANOS, System.nanoTime() - start);
    }

    private record CookieSelection(int count, @Nullable String value) {}
}
