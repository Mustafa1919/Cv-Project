package com.mstech.vitrin.platform.token;

import java.util.Optional;

public enum Role {
    GUEST_COMPANY("guest_company"),
    DEMO_CANDIDATE("demo_candidate");

    private final String claimValue;

    Role(String claimValue) {
        this.claimValue = claimValue;
    }

    public String claimValue() {
        return claimValue;
    }

    public static Optional<Role> fromClaim(String claim) {
        for (Role role : values()) {
            if (role.claimValue.equals(claim)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
