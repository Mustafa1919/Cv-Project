package com.mstech.vitrin.gateway.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.ACCESS_LOG)
public final class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccessLogFilter.class);

    public AccessLogFilter() {}

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long enteredNanos = System.nanoTime();
        Long edgeStartNanos = RequestAttributes.startNanos(request);
        long startNanos = edgeStartNanos == null ? enteredNanos : edgeStartNanos.longValue();
        boolean completed = false;
        try {
            chain.doFilter(request, response);
            completed = true;
        } finally {
            var route = RequestAttributes.route(request);
            int status =
                    !completed && !response.isCommitted()
                            ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                            : response.getStatus();
            long durationMillis = Math.max(0L, System.nanoTime() - startNanos) / 1_000_000L;

            // Log only bounded metadata, never client-controlled text or sensitive request data.
            LOGGER.atInfo()
                    .addKeyValue("http.request.method", logMethod(request.getMethod()))
                    .addKeyValue("http.route", route == null ? "unmatched" : route.id())
                    .addKeyValue("http.response.status_code", Integer.valueOf(status))
                    .addKeyValue("duration_ms", Long.valueOf(durationMillis))
                    .log("request");
        }
    }

    private static String logMethod(@Nullable String method) {
        if (method == null) {
            return "_OTHER";
        }
        return switch (method) {
            case "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS" -> method;
            default -> "_OTHER";
        };
    }
}
