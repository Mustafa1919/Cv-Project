package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.TokenRejectedException;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(Ordered.HIGHEST_PRECEDENCE + 20)
final class AccessTokenFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(AccessTokenFilter.class);
    private static final Pattern PATH_PARAMETERS = Pattern.compile(";[^/]*");
    private static final List<Rule> POLICY =
            List.of(
                    new Rule("/internal", null, true, Requirement.NONE),
                    new Rule("/v1/session", "POST", false, Requirement.OPTIONAL),
                    new Rule("/v1/session/refresh", "POST", false, Requirement.OPTIONAL),
                    new Rule("/v1", null, true, Requirement.REQUIRED));

    private final TokenVerifier verifier;
    private final IdentityProblems problems;

    AccessTokenFilter(TokenVerifier verifier, IdentityProblems problems) {
        this.verifier = verifier;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        String dispatchedPath = request.getServletPath() + (pathInfo == null ? "" : pathInfo);
        String path = PATH_PARAMETERS.matcher(dispatchedPath).replaceAll("");

        if (path.equals("/v1") || path.startsWith("/v1/")) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        if (ambiguousPath(request.getRequestURI()) || ambiguousPath(dispatchedPath)) {
            problems.write(response, HttpStatus.BAD_REQUEST, "PATH_NOT_ALLOWED");
            return;
        }

        Requirement requirement = requirement(request.getMethod(), path);
        if (requirement == Requirement.NONE) {
            chain.doFilter(request, response);
            return;
        }

        String compact = IdentityCookies.read(request, IdentityCookies.ACCESS_NAME);
        VerifiedToken verified = null;
        if (compact != null) {
            try {
                verified = verifier.verify(compact, TokenKind.ACCESS);
            } catch (TokenRejectedException exception) {
                LOGGER.debug("Access token rejected: {}", exception.reason());
            }
        }
        if (verified == null) {
            if (requirement == Requirement.REQUIRED) {
                problems.write(
                        response,
                        HttpStatus.UNAUTHORIZED,
                        compact == null ? "ACCESS_TOKEN_MISSING" : "ACCESS_TOKEN_INVALID");
            } else {
                chain.doFilter(request, response);
            }
            return;
        }
        RequestIdentity identity =
                new RequestIdentity(verified.sessionId(), verified.role(), verified.expiresAt());

        // CallableOp permits one checked exception type; bridge IOException only.
        ScopedValue.CallableOp<@Nullable Void, ServletException> operation =
                () -> {
                    try {
                        chain.doFilter(request, response);
                    } catch (IOException exception) {
                        throw new ChainIOException(exception);
                    }
                    return null;
                };
        try {
            ScopedValue.where(RequestIdentity.CURRENT, identity).call(operation);
        } catch (ChainIOException exception) {
            throw exception.ioException();
        }
    }

    private static boolean ambiguousPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains(";")
                || lower.contains("//")
                || lower.contains("/./")
                || lower.contains("/../")
                || lower.endsWith("/.")
                || lower.endsWith("/..")
                || lower.contains("%2f")
                || lower.contains("%2e")
                || lower.contains("%5c")
                || lower.contains("\\");
    }

    private static Requirement requirement(String method, String path) {
        for (Rule rule : POLICY) {
            if (rule.matches(method, path)) {
                return rule.requirement();
            }
        }
        return Requirement.NONE;
    }

    private enum Requirement {
        NONE,
        OPTIONAL,
        REQUIRED
    }

    private record Rule(
            String path, @Nullable String method, boolean subtree, Requirement requirement) {
        boolean matches(String requestMethod, String requestPath) {
            return (method == null || method.equals(requestMethod))
                    && (path.equals(requestPath)
                            || (subtree && requestPath.startsWith(path + "/")));
        }
    }

    private static final class ChainIOException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final IOException ioException;

        private ChainIOException(IOException ioException) {
            super("Request chain I/O failure", ioException);
            this.ioException = ioException;
        }

        private IOException ioException() {
            return ioException;
        }
    }
}
