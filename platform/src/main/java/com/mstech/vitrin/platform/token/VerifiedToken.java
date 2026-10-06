package com.mstech.vitrin.platform.token;

import java.time.Instant;

public record VerifiedToken(
        TokenKind kind, String sessionId, Role role, Instant issuedAt, Instant expiresAt) {}
