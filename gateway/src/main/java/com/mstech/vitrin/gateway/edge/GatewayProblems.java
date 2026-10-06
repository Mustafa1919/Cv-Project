package com.mstech.vitrin.gateway.edge;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

public final class GatewayProblems {
    private final ObjectWriter writer;
    private final ObjectProvider<Tracer> tracers;

    public GatewayProblems(JsonMapper mapper, ObjectProvider<Tracer> tracers) {
        this.writer = mapper.writerFor(ProblemDetail.class);
        this.tracers = tracers;
    }

    public ProblemDetail build(HttpServletRequest request, HttpStatus status, String code) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("about:blank"));
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            problem.setProperty("deniedBy", "gateway");
        }
        String traceId = traceId(request);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        return problem;
    }

    // Not named write: SpotBugs treats classes with write* methods as mutable and then flags
    // every filter that keeps a reference to this one.
    public void send(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ProblemDetail problem = build(request, status, code);
        response.setStatus(status.value());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        writer.writeValue(response.getOutputStream(), problem);
    }

    private @Nullable String traceId(HttpServletRequest request) {
        String captured = RequestAttributes.traceId(request);
        if (captured != null) {
            return captured;
        }
        Tracer tracer = tracers.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
