package com.mstech.vitrin.gateway.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.EDGE)
public final class EdgeFilter extends OncePerRequestFilter {
    private static final Set<String> STRIPPED_HEADERS =
            Set.of(
                    "traceparent",
                    "tracestate",
                    "baggage",
                    "forwarded",
                    "x-real-ip",
                    "true-client-ip",
                    "x-trace-id",
                    "x-session-id",
                    "x-user-id",
                    "x-role",
                    "x-degraded");

    private final ClientAddressResolver addresses;
    private final String clientAddressHeader;

    public EdgeFilter(ClientAddressResolver addresses, GatewayProperties properties) {
        this.addresses = addresses;
        this.clientAddressHeader = properties.clientAddressHeader().toLowerCase(Locale.ROOT);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(RequestAttributes.START_NANOS, System.nanoTime());
        request.setAttribute(RequestAttributes.CLIENT_ADDRESS, addresses.resolve(request));
        HttpServletRequest sanitized = new SanitizedRequest(request, clientAddressHeader);
        CommitHookResponse hooked =
                new CommitHookResponse(response, () -> decorateResponse(sanitized, response));
        try {
            chain.doFilter(sanitized, hooked);
        } finally {
            if (!hooked.isCommitted()) {
                hooked.runHook();
            }
        }
    }

    private static void decorateResponse(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        String traceId = RequestAttributes.traceId(request);
        if (traceId != null) {
            response.setHeader("X-Trace-Id", traceId);
        }
        response.setHeader("Server-Timing", ServerTiming.build(request));
    }

    private static final class SanitizedRequest extends HttpServletRequestWrapper {
        private final String clientAddressHeader;

        private SanitizedRequest(HttpServletRequest request, String clientAddressHeader) {
            super(request);
            this.clientAddressHeader = clientAddressHeader;
        }

        private boolean stripped(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return STRIPPED_HEADERS.contains(lower)
                    || lower.equals(clientAddressHeader)
                    || lower.startsWith("x-forwarded-")
                    || lower.startsWith("x-vitrin-")
                    || lower.startsWith("cf-")
                    || lower.startsWith("x-b3-");
        }

        @Override
        public @Nullable String getHeader(String name) {
            return stripped(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (stripped(name)) {
                return Collections.emptyEnumeration();
            }
            Enumeration<String> values = super.getHeaders(name);
            return values == null ? Collections.emptyEnumeration() : values;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            if (names == null) {
                return Collections.emptyEnumeration();
            }
            List<String> visible = new ArrayList<>();
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (!stripped(name)) {
                    visible.add(name);
                }
            }
            return Collections.enumeration(visible);
        }

        @Override
        public int getIntHeader(String name) {
            return stripped(name) ? -1 : super.getIntHeader(name);
        }

        @Override
        public long getDateHeader(String name) {
            return stripped(name) ? -1L : super.getDateHeader(name);
        }
    }
}
