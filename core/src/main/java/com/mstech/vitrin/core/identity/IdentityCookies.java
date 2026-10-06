package com.mstech.vitrin.core.identity;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseCookie;

final class IdentityCookies {
    static final String ACCESS_NAME = "__Host-vitrin_at";
    static final String REFRESH_NAME = "__Secure-vitrin_rt";

    private final Clock clock;

    IdentityCookies(Clock clock) {
        this.clock = clock;
    }

    String access(IssuedToken token) {
        return build(ACCESS_NAME, "/", token);
    }

    String refresh(IssuedToken token) {
        return build(REFRESH_NAME, "/v1/session/refresh", token);
    }

    private String build(String name, String path, IssuedToken token) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        long seconds = Math.max(0, Duration.between(now, token.expiresAt()).toSeconds());
        return ResponseCookie.from(name, token.value())
                .path(path)
                .secure(true)
                .httpOnly(true)
                .sameSite("Strict")
                .maxAge(seconds)
                .build()
                .toString();
    }

    static @Nullable String read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String found = null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                if (found != null) {
                    // Ambiguous credentials must not depend on servlet cookie ordering.
                    return "";
                }
                found = cookie.getValue();
            }
        }
        return found;
    }
}
