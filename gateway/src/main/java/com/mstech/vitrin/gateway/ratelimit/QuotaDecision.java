package com.mstech.vitrin.gateway.ratelimit;

public record QuotaDecision(
        boolean allowed, long limit, long remaining, long resetSeconds, long retryAfterSeconds) {}
