package com.mstech.vitrin.gateway.edge;

import java.util.Objects;

public record ClientAddress(String canonical, boolean ipv6) {
    public ClientAddress {
        Objects.requireNonNull(canonical, "canonical");
    }
}
