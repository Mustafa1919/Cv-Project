package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.gateway.edge.SiteProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Enumeration;

public final class CorsPolicy {
    public static final String ALLOWED_REQUEST_HEADERS = "X-Requested-With";
    public static final String MAX_AGE = "600";

    private static final String EXPOSED_HEADERS =
            "Server-Timing, X-Trace-Id, RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset,"
                    + " X-Degraded";

    private final String origin;

    public CorsPolicy(SiteProperties properties) {
        this.origin = properties.origin();
    }

    public void decorate(HttpServletRequest request, HttpServletResponse response) {
        response.addHeader("Vary", "Origin");
        if (originAllowed(request)) {
            response.setHeader("Access-Control-Allow-Origin", origin);
            response.setHeader("Access-Control-Allow-Credentials", "true");
            response.setHeader("Access-Control-Expose-Headers", EXPOSED_HEADERS);
            response.setHeader("Timing-Allow-Origin", origin);
        }
    }

    public boolean originAllowed(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders("Origin");
        if (values == null || !values.hasMoreElements()) {
            return false;
        }
        String value = values.nextElement();
        return !values.hasMoreElements() && origin.equals(value);
    }
}
