package com.mstech.vitrin.gateway.edge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.StringJoiner;

final class ServerTiming {
    private ServerTiming() {}

    static String build(HttpServletRequest request) {
        StringJoiner metrics = new StringJoiner(", ");
        Long authNanos = RequestAttributes.authNanos(request);
        if (authNanos != null) {
            metrics.add(metric("auth", authNanos));
        }
        Long upstreamNanos = RequestAttributes.upstreamNanos(request);
        if (upstreamNanos != null) {
            metrics.add(metric("core", upstreamNanos));
        }
        Long startNanos = RequestAttributes.startNanos(request);
        if (startNanos != null) {
            metrics.add(metric("total", System.nanoTime() - startNanos));
        }
        return metrics.toString();
    }

    private static String metric(String name, long nanos) {
        return String.format(Locale.ROOT, "%s;dur=%.2f", name, Math.max(0L, nanos) / 1_000_000.0);
    }
}
