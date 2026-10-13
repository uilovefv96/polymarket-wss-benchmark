package com.example.polymarket.core;

import java.time.Duration;

/** 可独立测试的连接恢复退避与代际判断。 */
final class RecoveryPolicy {
    private RecoveryPolicy() {}

    static long cappedDelayMillis(
            int attempt, boolean slowConsumer, Duration normalBase, Duration slowBase, Duration maximum) {
        long base = Math.max(0, (slowConsumer ? slowBase : normalBase).toMillis());
        long cap = Math.max(0, maximum.toMillis());
        int shift = Math.min(Math.max(attempt - 1, 0), 62);
        long exponential;
        if (base == 0) {
            exponential = 0;
        } else if (base > (Long.MAX_VALUE >> shift)) {
            exponential = Long.MAX_VALUE;
        } else {
            exponential = base << shift;
        }
        return Math.min(cap, exponential);
    }

    static boolean mayResetAttempts(long scheduledGeneration, long currentGeneration, boolean open) {
        return open && scheduledGeneration == currentGeneration;
    }
}
