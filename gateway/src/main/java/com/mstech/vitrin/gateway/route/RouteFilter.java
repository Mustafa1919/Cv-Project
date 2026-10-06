package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.ROUTE)
public final class RouteFilter extends OncePerRequestFilter {
    private final RouteTable routes;
    private final CorsPolicy cors;
    private final GatewayProblems problems;
    private final ObjectProvider<Tracer> tracers;

    public RouteFilter(
            RouteTable routes,
            CorsPolicy cors,
            GatewayProblems problems,
            ObjectProvider<Tracer> tracers) {
        this.routes = routes;
        this.cors = cors;
        this.problems = problems;
        this.tracers = tracers;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        captureTraceId(request);
        request.removeAttribute(RequestAttributes.ROUTE);
        request.removeAttribute(RequestAttributes.PREFLIGHT);
        cors.decorate(request, response);

        String pathInfo = request.getPathInfo();
        String dispatchPath = request.getServletPath() + (pathInfo == null ? "" : pathInfo);
        if (ambiguous(request.getRequestURI()) || ambiguous(dispatchPath)) {
            problems.send(request, response, HttpStatus.BAD_REQUEST, "PATH_NOT_ALLOWED");
            return;
        }

        String method = request.getMethod();
        if ("OPTIONS".equals(method)
                && request.getHeader("Origin") != null
                && request.getHeader("Access-Control-Request-Method") != null) {
            request.setAttribute(RequestAttributes.PREFLIGHT, true);
            String requestedMethod = singleRequestedMethod(request);
            if (requestedMethod == null) {
                problems.send(request, response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND");
                return;
            }
            method = requestedMethod;
        }

        RouteRule rule = routes.find(method, dispatchPath).orElse(null);
        if (rule == null) {
            problems.send(request, response, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND");
            return;
        }
        request.setAttribute(RequestAttributes.ROUTE, rule);
        chain.doFilter(request, response);
    }

    private void captureTraceId(HttpServletRequest request) {
        request.removeAttribute(RequestAttributes.TRACE_ID);
        Tracer tracer = tracers.getIfAvailable();
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                request.setAttribute(RequestAttributes.TRACE_ID, span.context().traceId());
            }
        }
    }

    private static @Nullable String singleRequestedMethod(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Access-Control-Request-Method");
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private static boolean ambiguous(@Nullable String path) {
        if (path == null) {
            return true;
        }
        for (int index = 0; index < path.length(); index++) {
            char character = path.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                return true;
            }
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains(";")
                || lower.contains("//")
                || lower.contains("/./")
                || lower.contains("/../")
                || lower.endsWith("/.")
                || lower.endsWith("/..")
                // No route needs percent-encoding; refusing it leaves one spelling per path.
                || lower.contains("%")
                || lower.contains("\\");
    }
}
