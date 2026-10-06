package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.PREFLIGHT)
public final class PreflightFilter extends OncePerRequestFilter {
    private final CorsPolicy cors;
    private final GatewayProblems problems;

    public PreflightFilter(CorsPolicy cors, GatewayProblems problems) {
        this.cors = cors;
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
        if (!RequestAttributes.preflight(request)) {
            chain.doFilter(request, response);
            return;
        }

        response.addHeader("Vary", "Access-Control-Request-Method");
        response.addHeader("Vary", "Access-Control-Request-Headers");
        if (!cors.originAllowed(request) || !requestHeadersAllowed(request)) {
            problems.send(request, response, HttpStatus.FORBIDDEN, "CORS_PREFLIGHT_REJECTED");
            return;
        }
        response.setHeader("Access-Control-Allow-Methods", rule.method());
        response.setHeader("Access-Control-Allow-Headers", CorsPolicy.ALLOWED_REQUEST_HEADERS);
        response.setHeader("Access-Control-Max-Age", CorsPolicy.MAX_AGE);
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }

    private static boolean requestHeadersAllowed(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Access-Control-Request-Headers");
        if (values == null) {
            return true;
        }
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            for (String name : value.split(",", -1)) {
                if (!CorsPolicy.ALLOWED_REQUEST_HEADERS.equalsIgnoreCase(name.trim())) {
                    return false;
                }
            }
        }
        return true;
    }
}
