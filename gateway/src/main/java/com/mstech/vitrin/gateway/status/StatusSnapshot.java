package com.mstech.vitrin.gateway.status;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record StatusSnapshot(String status, Map<String, String> components, Instant checkedAt) {
    public StatusSnapshot {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put("gateway", Objects.requireNonNull(components.get("gateway"), "gateway"));
        ordered.put("core", Objects.requireNonNull(components.get("core"), "core"));
        ordered.put("search", Objects.requireNonNull(components.get("search"), "search"));
        components = Collections.unmodifiableMap(ordered);
    }
}
