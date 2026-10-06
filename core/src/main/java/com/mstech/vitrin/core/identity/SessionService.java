package com.mstech.vitrin.core.identity;

import com.mstech.vitrin.platform.token.Role;
import com.mstech.vitrin.platform.token.TokenKind;
import com.mstech.vitrin.platform.token.TokenRejectedException;
import com.mstech.vitrin.platform.token.TokenVerifier;
import com.mstech.vitrin.platform.token.VerifiedToken;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

final class SessionService {
    private final IdentityProperties properties;
    private final TokenIssuer issuer;
    private final TokenVerifier verifier;
    private final Clock clock;
    private final SecureRandom random;

    SessionService(
            IdentityProperties properties,
            TokenIssuer issuer,
            TokenVerifier verifier,
            Clock clock,
            SecureRandom random) {
        this.properties = properties;
        this.issuer = issuer;
        this.verifier = verifier;
        this.clock = clock;
        this.random = random;
    }

    NewSession create() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        String sessionId = HexFormat.of().formatHex(bytes);
        Instant sessionExpiry =
                clock.instant().plus(properties.guestSessionTtl()).truncatedTo(ChronoUnit.SECONDS);
        IssuedToken access = issuer.access(sessionId, Role.GUEST_COMPANY, sessionExpiry);
        IssuedToken refresh = issuer.refresh(sessionId, Role.GUEST_COMPANY, sessionExpiry);
        return new NewSession(
                new RequestIdentity(sessionId, Role.GUEST_COMPANY, access.expiresAt()),
                access,
                refresh);
    }

    RefreshedSession refresh(String compact) throws TokenRejectedException {
        VerifiedToken verified = verifier.verify(compact, TokenKind.REFRESH);
        IssuedToken access =
                issuer.access(verified.sessionId(), verified.role(), verified.expiresAt());
        return new RefreshedSession(
                new RequestIdentity(verified.sessionId(), verified.role(), access.expiresAt()),
                access);
    }

    record NewSession(RequestIdentity identity, IssuedToken access, IssuedToken refresh) {}

    record RefreshedSession(RequestIdentity identity, IssuedToken access) {}
}
