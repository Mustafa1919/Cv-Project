package com.mstech.vitrin.gateway.auth;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.CorsPolicy;
import com.mstech.vitrin.gateway.route.RouteRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.STATE_CHANGE)
public final class StateChangeFilter extends OncePerRequestFilter {
    private final CorsPolicy cors;
    private final GatewayProblems problems;

    public StateChangeFilter(CorsPolicy cors, GatewayProblems problems) {
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
        if (rule.stateChanging()) {
            if (!cors.originAllowed(request)) {
                problems.send(request, response, HttpStatus.FORBIDDEN, "ORIGIN_NOT_ALLOWED");
                return;
            }
            if (!requestedWithAllowed(request)) {
                problems.send(request, response, HttpStatus.FORBIDDEN, "REQUESTED_WITH_REQUIRED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean requestedWithAllowed(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("X-Requested-With");
        if (values == null || !values.hasMoreElements()) {
            return false;
        }
        String value = values.nextElement();
        return !values.hasMoreElements() && "vitrin".equals(value);
    }
}
