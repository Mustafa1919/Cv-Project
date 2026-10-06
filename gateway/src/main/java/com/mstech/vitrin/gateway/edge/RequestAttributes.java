package com.mstech.vitrin.gateway.edge;

import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;

public final class RequestAttributes {
    public static final String CLIENT_ADDRESS = "vitrin.clientAddress";
    public static final String START_NANOS = "vitrin.startNanos";
    public static final String TRACE_ID = "vitrin.traceId";
    public static final String ROUTE = "vitrin.route";
    public static final String TOKEN = "vitrin.token";
    public static final String AUTH_NANOS = "vitrin.authNanos";
    public static final String UPSTREAM_NANOS = "vitrin.upstreamNanos";
    public static final String PREFLIGHT = "vitrin.preflight";

    private RequestAttributes() {}

    public static @Nullable ClientAddress clientAddress(HttpServletRequest request) {
        Object value = request.getAttribute(CLIENT_ADDRESS);
        return value instanceof ClientAddress address ? address : null;
    }

    public static @Nullable Long startNanos(HttpServletRequest request) {
        Object value = request.getAttribute(START_NANOS);
        return value instanceof Long nanos ? nanos : null;
    }

    public static @Nullable String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TRACE_ID);
        return value instanceof String traceId ? traceId : null;
    }

    public static @Nullable RouteRule route(HttpServletRequest request) {
        Object value = request.getAttribute(ROUTE);
        return value instanceof RouteRule rule ? rule : null;
    }

    public static @Nullable VerifiedToken token(HttpServletRequest request) {
        Object value = request.getAttribute(TOKEN);
        return value instanceof VerifiedToken token ? token : null;
    }

    public static @Nullable Long authNanos(HttpServletRequest request) {
        Object value = request.getAttribute(AUTH_NANOS);
        return value instanceof Long nanos ? nanos : null;
    }

    public static @Nullable Long upstreamNanos(HttpServletRequest request) {
        Object value = request.getAttribute(UPSTREAM_NANOS);
        return value instanceof Long nanos ? nanos : null;
    }

    public static boolean preflight(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(PREFLIGHT));
    }
}
