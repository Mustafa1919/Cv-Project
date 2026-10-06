package com.mstech.vitrin.gateway.ratelimit;

import com.mstech.vitrin.gateway.edge.GatewayProblems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;

final class QuotaResponses {
    private QuotaResponses() {}

    static boolean allow(
            HttpServletRequest request,
            HttpServletResponse response,
            QuotaDecision decision,
            GatewayProblems problems)
            throws IOException {
        response.setHeader("RateLimit-Limit", Long.toString(decision.limit()));
        response.setHeader("RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("RateLimit-Reset", Long.toString(decision.resetSeconds()));
        if (decision.allowed()) {
            return true;
        }
        response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
        problems.send(request, response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED");
        return false;
    }
}
