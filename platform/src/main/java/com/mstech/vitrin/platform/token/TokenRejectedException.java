package com.mstech.vitrin.platform.token;

public final class TokenRejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        MALFORMED,
        UNSUPPORTED_ALGORITHM,
        WRONG_TYPE,
        UNKNOWN_KEY,
        BAD_SIGNATURE,
        WRONG_ISSUER,
        WRONG_AUDIENCE,
        INVALID_CLAIMS,
        UNKNOWN_ROLE,
        NOT_YET_VALID,
        EXPIRED
    }

    private final Reason reason;

    public TokenRejectedException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
