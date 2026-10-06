package com.mstech.vitrin.gateway.route;

import com.mstech.vitrin.platform.token.Role;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RouteTable {
    private final List<RouteRule> rules;
    private final Map<RouteKey, RouteRule> index;
    private final Map<String, Set<String>> methodsByPath;

    public RouteTable(List<RouteRule> rules) {
        this.rules = List.copyOf(rules);
        Map<RouteKey, RouteRule> byRoute = new HashMap<>();
        Map<String, Set<String>> methods = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (RouteRule rule : this.rules) {
            if (!ids.add(rule.id())) {
                throw new IllegalArgumentException("Duplicate route id");
            }
            RouteKey key = new RouteKey(rule.method(), rule.path());
            if (byRoute.putIfAbsent(key, rule) != null) {
                throw new IllegalArgumentException("Duplicate route method and path");
            }
            methods.computeIfAbsent(rule.path(), ignored -> new HashSet<>()).add(rule.method());
        }
        Map<String, Set<String>> immutableMethods = new HashMap<>();
        methods.forEach((path, values) -> immutableMethods.put(path, Set.copyOf(values)));
        this.index = Map.copyOf(byRoute);
        this.methodsByPath = Map.copyOf(immutableMethods);
    }

    public Optional<RouteRule> find(String method, String path) {
        return Optional.ofNullable(index.get(new RouteKey(method, path)));
    }

    public List<RouteRule> rules() {
        return rules;
    }

    public Set<String> methodsFor(String path) {
        return methodsByPath.getOrDefault(path, Set.of());
    }

    public static RouteTable pillarZero() {
        return new RouteTable(
                List.of(
                        new RouteRule(
                                "status",
                                "GET",
                                "/v1/public/status",
                                Upstream.LOCAL,
                                TokenRequirement.NONE,
                                Set.of(),
                                "public",
                                null,
                                false,
                                false),
                        new RouteRule(
                                "session-create",
                                "POST",
                                "/v1/session",
                                Upstream.CORE,
                                TokenRequirement.NONE,
                                Set.of(),
                                "session-create",
                                null,
                                false,
                                false),
                        new RouteRule(
                                "session-read",
                                "GET",
                                "/v1/session",
                                Upstream.CORE,
                                TokenRequirement.REQUIRED,
                                Set.of(Role.GUEST_COMPANY, Role.DEMO_CANDIDATE),
                                "authenticated",
                                "session-read",
                                false,
                                false),
                        new RouteRule(
                                "session-refresh",
                                "POST",
                                "/v1/session/refresh",
                                Upstream.CORE,
                                TokenRequirement.NONE,
                                Set.of(),
                                "session-refresh",
                                null,
                                false,
                                false)));
    }

    private record RouteKey(String method, String path) {}
}
