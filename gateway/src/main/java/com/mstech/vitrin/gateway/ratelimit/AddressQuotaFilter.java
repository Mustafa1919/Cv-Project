package com.mstech.vitrin.gateway.ratelimit;

import com.mstech.vitrin.gateway.edge.ClientAddress;
import com.mstech.vitrin.gateway.edge.FilterOrder;
import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

@Order(FilterOrder.ADDRESS_QUOTA)
public final class AddressQuotaFilter extends OncePerRequestFilter {
    private final QuotaStore quotas;
    private final RateLimitProperties properties;
    private final AddressHasher hasher;
    private final GatewayProblems problems;

    public AddressQuotaFilter(
            QuotaStore quotas,
            RateLimitProperties properties,
            AddressHasher hasher,
            GatewayProblems problems) {
        this.quotas = quotas;
        this.properties = properties;
        this.hasher = hasher;
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
        String group =
                RequestAttributes.preflight(request) ? "preflight" : rule.addressQuotaGroup();
        if (group == null) {
            chain.doFilter(request, response);
            return;
        }
        ClientAddress address = RequestAttributes.clientAddress(request);
        if (address == null) {
            misconfigured(request, response);
            return;
        }
        String key = "rl:a:" + group + ":" + hasher.hash(address.canonical());
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
