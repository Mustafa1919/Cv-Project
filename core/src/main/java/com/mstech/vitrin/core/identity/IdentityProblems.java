package com.mstech.vitrin.core.identity;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

final class IdentityProblems {
    private final JsonMapper mapper;
    private final ObjectProvider<Tracer> tracers;

    IdentityProblems(JsonMapper mapper, ObjectProvider<Tracer> tracers) {
        this.mapper = mapper;
        this.tracers = tracers;
    }

    ProblemDetail build(HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        // Says where an authentication or authorization decision was made; other errors omit it.
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            problem.setProperty("deniedBy", "core");
        }
        Tracer tracer = tracers.getIfAvailable();
        if (tracer != null) {
            Span span = tracer.currentSpan();
            if (span != null) {
                problem.setProperty("traceId", span.context().traceId());
            }
        }
        return problem;
    }

    void write(HttpServletResponse response, HttpStatus status, String code) throws IOException {
        ProblemDetail problem = build(status, code);
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
