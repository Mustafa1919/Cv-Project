package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.platform.token.Role;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public record RouteRule(
        String id,
        String method,
        String path,
        Upstream upstream,
        TokenRequirement token,
        Set<Role> roles,
        @Nullable String addressQuotaGroup,
        @Nullable String sessionQuotaGroup,
        boolean queryAllowed,
        boolean bodyAllowed) {

    public RouteRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(token, "token");
        roles = Set.copyOf(roles);
        if (id.isBlank() || !method.matches("[A-Z]+") || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid route rule");
        }
        if (token == TokenRequirement.NONE && (!roles.isEmpty() || sessionQuotaGroup != null)) {
            throw new IllegalArgumentException("Unauthenticated route has authentication policy");
        }
        if (token == TokenRequirement.REQUIRED && roles.isEmpty()) {
            throw new IllegalArgumentException("Authenticated route has no allowed roles");
        }
        if ((addressQuotaGroup != null && addressQuotaGroup.isBlank())
                || (sessionQuotaGroup != null && sessionQuotaGroup.isBlank())) {
            throw new IllegalArgumentException("Invalid route quota group");
        }
    }

    public boolean stateChanging() {
        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> true;
            default -> false;
        };
    }
}
