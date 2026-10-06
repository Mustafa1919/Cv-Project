package com.mstech.vitrin.gateway.ratelimit;

import io.github.bucket4j.ConsumptionProbe;

final class QuotaDecisions {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private QuotaDecisions() {}

    static QuotaDecision fromProbe(ConsumptionProbe probe, int limitPerMinute) {
        boolean allowed = probe.isConsumed();
        long retryAfter =
                allowed ? 0L : Math.max(1L, roundedSeconds(probe.getNanosToWaitForRefill()));
        return new QuotaDecision(
                allowed,
                limitPerMinute,
                probe.getRemainingTokens(),
                roundedSeconds(probe.getNanosToWaitForReset()),
                retryAfter);
    }

    private static long roundedSeconds(long nanos) {
        if (nanos <= 0) {
            return 0L;
        }
        return nanos / NANOS_PER_SECOND + (nanos % NANOS_PER_SECOND == 0 ? 0L : 1L);
    }
}
