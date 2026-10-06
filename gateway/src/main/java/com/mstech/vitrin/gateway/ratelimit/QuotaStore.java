package com.mstech.vitrin.gateway.ratelimit;

@FunctionalInterface
public interface QuotaStore {
    QuotaDecision tryConsume(String key, int limitPerMinute);
}
