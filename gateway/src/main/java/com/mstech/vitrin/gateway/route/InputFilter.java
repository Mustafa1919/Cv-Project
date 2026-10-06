package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.INPUT)
public final class InputFilter extends OncePerRequestFilter {
    private final GatewayProblems problems;

    public InputFilter(GatewayProblems problems) {
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
        if (!rule.queryAllowed() && request.getQueryString() != null) {
            problems.send(request, response, HttpStatus.BAD_REQUEST, "QUERY_NOT_ALLOWED");
            return;
        }
        if (!rule.bodyAllowed()
                && (request.getContentLengthLong() > 0
                        || request.getHeader("Transfer-Encoding") != null)) {
            problems.send(request, response, HttpStatus.BAD_REQUEST, "BODY_NOT_ALLOWED");
            return;
        }
        chain.doFilter(request, response);
    }
}
