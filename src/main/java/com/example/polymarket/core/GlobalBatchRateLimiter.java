package com.example.polymarket.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/** 进程内所有连接共享的匀速批次许可；0 表示不限速。 */
final class GlobalBatchRateLimiter {
    private final long intervalNanos;
    private final AtomicLong nextPermitNanos = new AtomicLong();

    GlobalBatchRateLimiter(int permitsPerSecond) {
        intervalNanos = permitsPerSecond <= 0 ? 0 : TimeUnit.SECONDS.toNanos(1) / permitsPerSecond;
    }

    long reserveDelayNanos(long nowNanos) {
        if (intervalNanos == 0) {
            return 0;
        }
        while (true) {
            long current = nextPermitNanos.get();
            long granted = Math.max(current, nowNanos);
            long next = granted > Long.MAX_VALUE - intervalNanos
                    ? Long.MAX_VALUE
                    : granted + intervalNanos;
            if (nextPermitNanos.compareAndSet(current, next)) {
                return Math.max(0, granted - nowNanos);
            }
        }
    }

    void acquire() throws InterruptedException {
        long remaining = reserveDelayNanos(System.nanoTime());
        while (remaining > 0) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long started = System.nanoTime();
            LockSupport.parkNanos(remaining);
            remaining -= Math.max(0, System.nanoTime() - started);
        }
    }
}
