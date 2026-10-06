package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.TokenRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/session")
final class SessionController {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionController.class);

    private final SessionService sessions;
    private final IdentityCookies cookies;
    private final IdentityProblems problems;

    SessionController(SessionService sessions, IdentityCookies cookies, IdentityProblems problems) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.problems = problems;
    }

    @PostMapping
    ResponseEntity<?> create(HttpServletRequest request) {
        if (declaresBody(request)) {
            return problem(HttpStatus.BAD_REQUEST, "BODY_NOT_ALLOWED");
        }
        if (RequestIdentity.CURRENT.isBound()) {
            return response(HttpStatus.OK).body(body(RequestIdentity.required()));
        }
        SessionService.NewSession created = sessions.create();
        return response(HttpStatus.CREATED)
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.access(created.access()),
                        cookies.refresh(created.refresh()))
                .body(body(created.identity()));
    }

    @PostMapping("/refresh")
    ResponseEntity<?> refresh(HttpServletRequest request) {
        if (declaresBody(request)) {
            return problem(HttpStatus.BAD_REQUEST, "BODY_NOT_ALLOWED");
        }
        String compact = IdentityCookies.read(request, IdentityCookies.REFRESH_NAME);
        if (compact == null) {
            return problem(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_MISSING");
        }
        try {
            SessionService.RefreshedSession refreshed = sessions.refresh(compact);
            return response(HttpStatus.OK)
                    .header(HttpHeaders.SET_COOKIE, cookies.access(refreshed.access()))
                    .body(body(refreshed.identity()));
        } catch (TokenRejectedException exception) {
            LOGGER.debug("Refresh token rejected: {}", exception.reason());
            return problem(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_INVALID");
        }
    }

    @GetMapping
    ResponseEntity<SessionBody> current() {
        return response(HttpStatus.OK).body(body(RequestIdentity.required()));
    }

    private static boolean declaresBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0
                || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problems.build(status, code));
    }

    private static ResponseEntity.BodyBuilder response(HttpStatus status) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON);
    }

    private static SessionBody body(RequestIdentity identity) {
        return new SessionBody(
                identity.role().claimValue(),
                identity.shortSessionId(),
                identity.accessExpiresAt());
    }

    record SessionBody(String role, String sessionShortId, Instant accessExpiresAt) {}
}
