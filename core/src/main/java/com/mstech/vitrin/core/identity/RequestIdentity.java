package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.Role;
import java.time.Instant;

public record RequestIdentity(String sessionId, Role role, Instant accessExpiresAt) {
    public static final ScopedValue<RequestIdentity> CURRENT = ScopedValue.newInstance();

    public static RequestIdentity required() {
        if (!CURRENT.isBound()) {
            throw new IllegalStateException("Request identity is not bound");
        }
        return CURRENT.get();
    }

    public String shortSessionId() {
        return sessionId.substring(0, 4) + "…" + sessionId.substring(sessionId.length() - 4);
    }
}
