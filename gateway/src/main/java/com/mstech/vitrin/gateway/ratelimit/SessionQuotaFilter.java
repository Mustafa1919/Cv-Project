package com.mstech.vitrin.gateway.ratelimit;

import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.platform.token.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.SESSION_QUOTA)
public final class SessionQuotaFilter extends OncePerRequestFilter {
    private final QuotaStore quotas;
    private final RateLimitProperties properties;
    private final GatewayProblems problems;

    public SessionQuotaFilter(
            QuotaStore quotas, RateLimitProperties properties, GatewayProblems problems) {
        this.quotas = quotas;
        this.properties = properties;
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
        String group = rule.sessionQuotaGroup();
        if (group == null) {
            chain.doFilter(request, response);
            return;
        }
        VerifiedToken token = RequestAttributes.token(request);
        if (token == null) {
            misconfigured(request, response);
            return;
        }
        String key = "rl:s:" + group + ":" + token.sessionId();
        QuotaDecision decision = quotas.tryConsume(key, properties.limitFor(group));
        if (QuotaResponses.allow(request, response, decision, problems)) {
            chain.doFilter(request, response);
        }
    }

    private void misconfigured(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        problems.send(request, response, HttpStatus.INTERNAL_SERVER_ERROR, "GATEWAY_MISCONFIGURED");
    }
}
