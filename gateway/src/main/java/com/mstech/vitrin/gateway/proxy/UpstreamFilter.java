package com.mstech.vitrin.gateway.proxy;

import com.mstech.vitrin.gateway.edge.GatewayProblems;
import com.mstech.vitrin.gateway.edge.RequestAttributes;
import com.mstech.vitrin.gateway.route.RouteRule;
import com.mstech.vitrin.gateway.route.Upstream;
import jakarta.servlet.http.HttpServletRequest;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

public final class UpstreamFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpstreamFilter.class);

    private final GatewayProblems problems;

    public UpstreamFilter(GatewayProblems problems) {
        this.problems = problems;
    }

    @Override
    public ServerResponse filter(ServerRequest request, HandlerFunction<ServerResponse> next)
            throws Exception {
        HttpServletRequest servletRequest = request.servletRequest();
        RouteRule rule = RequestAttributes.route(servletRequest);
        if (rule == null || rule.upstream() != Upstream.CORE) {
            return problem(
                    servletRequest,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "GATEWAY_MISCONFIGURED",
                    false);
        }

        long start = System.nanoTime();
        try {
            return next.handle(request);
        } catch (ResourceAccessException exception) {
            LOGGER.warn(
                    "Upstream call failed on route {}: {}",
                    rule.id(),
                    exception.getClass().getName());
            if (hasTimeoutCause(exception)) {
                return problem(
                        servletRequest, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", false);
            }
            return problem(
                    servletRequest, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE", true);
        } catch (RestClientException exception) {
            LOGGER.warn(
                    "Upstream call failed on route {}: {}",
                    rule.id(),
                    exception.getClass().getName());
            return problem(servletRequest, HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", false);
        } finally {
            servletRequest.setAttribute(
                    RequestAttributes.UPSTREAM_NANOS, System.nanoTime() - start);
        }
    }

    private ServerResponse problem(
            HttpServletRequest request, HttpStatus status, String code, boolean retry) {
        ServerResponse.BodyBuilder response =
                ServerResponse.status(status)
                        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .header("Cache-Control", "no-store");
        if (retry) {
            response.header("Retry-After", "5");
        }
        return response.body(problems.build(request, status, code));
    }

    private static boolean hasTimeoutCause(Throwable exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        @Nullable Throwable current = exception;
        while (current != null && visited.add(current)) {
            if (current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
