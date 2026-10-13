package com.example.polymarket.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class GlobalBatchRateLimiterTest {
    @Test
    void spacesReservationsAcrossAllCallers() {
        GlobalBatchRateLimiter limiter = new GlobalBatchRateLimiter(4);
        long now = 1_000_000_000L;

        assertThat(limiter.reserveDelayNanos(now)).isZero();
        assertThat(limiter.reserveDelayNanos(now)).isEqualTo(TimeUnit.MILLISECONDS.toNanos(250));
        assertThat(limiter.reserveDelayNanos(now)).isEqualTo(TimeUnit.MILLISECONDS.toNanos(500));
    }

    @Test
    void zeroPermitsMeansUnlimited() {
        GlobalBatchRateLimiter limiter = new GlobalBatchRateLimiter(0);
        assertThat(limiter.reserveDelayNanos(1)).isZero();
        assertThat(limiter.reserveDelayNanos(1)).isZero();
    }
}
