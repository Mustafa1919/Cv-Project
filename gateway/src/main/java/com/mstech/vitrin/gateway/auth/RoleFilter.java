package com.mstech.vitrin.gateway.auth;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.TokenRequirement;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.ROLE)
public final class RoleFilter extends OncePerRequestFilter {
    private final GatewayProblems problems;

    public RoleFilter(GatewayProblems problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteRule rule = RequestAttributes.route(request);
        if (rule == null) {
            misconfigured(request, response);
            return;
        }
        if (rule.token() == TokenRequirement.REQUIRED) {
            VerifiedToken token = RequestAttributes.token(request);
            if (token == null) {
                misconfigured(request, response);
                return;
            }
            if (!rule.roles().contains(token.role())) {
                problems.send(request, response, HttpStatus.FORBIDDEN, "ROLE_NOT_ALLOWED");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private void misconfigured(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problems.send(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "GATEWAY_MISCONFIGURED");
    }
}
