package com.example.polymarket.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.polymarket.config.BenchmarkProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BenchmarkMetricsTest {
    @Test
    void capturesPeakContextsAndInterFrameGap() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 7);
        metrics.startPhase("p1");
        Instant first = Instant.parse("2026-10-13T10:00:00Z");
        Instant second = first.plusMillis(5);

        metrics.frame(0, 7, 100, "book", first.toEpochMilli(), 1_000, 20_000, true);
        metrics.frame(
                0,
                7,
                250,
                "price_change",
                second.toEpochMilli(),
                6_000,
                10_000,
                true);

        var snapshot = metrics.snapshot(new int[] {1}).getFirst();
        assertThat(snapshot.maxFrameContext())
                .isEqualTo(new BenchmarkMetrics.FramePeak(second, "price_change", 250));
        assertThat(snapshot.maxCallbackContext())
                .isEqualTo(new BenchmarkMetrics.CallbackPeak(first, "book", 100, 20_000));
        assertThat(snapshot.lastFrameAt()).isEqualTo(second);
        assertThat(snapshot.maxFrameAt()).isEqualTo(second);
        assertThat(snapshot.maxInterFrameGapNs()).isEqualTo(5_000);
        assertThat(snapshot.eventTypes()).containsEntry("price_change", 1L);
    }

    @Test
    void addsCumulativeKiBFieldsToSnapshot() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 1);
        metrics.frame(0, 1, 3072, "book", System.currentTimeMillis(), 1_000, 2_000, true);
        metrics.frame(0, 1, 1024, "book", System.currentTimeMillis(), 2_000, 2_000, true);

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();

        assertThat(snapshot.bytes()).isEqualTo(4096);
        assertThat(snapshot.kibibytes()).isEqualTo(4);
        assertThat(snapshot.framesPerKiB()).isEqualTo(0.5);
    }

    @Test
    void reportsZeroCumulativeFramesPerKiBWhenNoBytesWereReceived() {
        var snapshot = metrics().snapshot(new int[] {0}).getFirst();

        assertThat(snapshot.kibibytes()).isZero();
        assertThat(snapshot.framesPerKiB()).isZero();
    }

    @Test
    void reportsDeterministicHundredMillisecondAndOneSecondWindows() {
        AtomicLong nowNs = new AtomicLong(1_000_000_000L);
        BenchmarkMetrics metrics = metrics(nowNs);
        metrics.publishGeneration(0, 1);
        metrics.frame(0, 1, 1024, "book", 1, 910_000_000L, 1, true);
        metrics.frame(0, 1, 2048, "book", 1, 500_000_000L, 1, true);
        metrics.frame(0, 1, 4096, "book", 1, 0, 1, true);

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();

        assertThat(snapshot.frames100ms()).isEqualTo(1);
        assertThat(snapshot.bytes100ms()).isEqualTo(1024);
        assertThat(snapshot.kibibytes100ms()).isEqualTo(1);
        assertThat(snapshot.framesPerKiB100ms()).isEqualTo(1);
        assertThat(snapshot.frames1s()).isEqualTo(2);
        assertThat(snapshot.bytes1s()).isEqualTo(3072);
        assertThat(snapshot.kibibytes1s()).isEqualTo(3);
        assertThat(snapshot.framesPerKiB1s()).isEqualTo(2.0 / 3);

        nowNs.set(1_020_000_000L);
        snapshot = metrics.snapshot(new int[] {0}).getFirst();
        assertThat(snapshot.frames100ms()).isZero();
        assertThat(snapshot.frames1s()).isEqualTo(2);
    }

    @Test
    void ignoresFramesFromOldGeneration() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 2);

        metrics.frame(0, 1, 100, "book", System.currentTimeMillis(), 1_000, 2_000, true);

        assertThat(metrics.snapshot(new int[] {0}).getFirst().frames()).isZero();
    }

    @Test
    void phaseResetClearsPhasePeaksButPreservesConnectionLifecycle() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 3);
        metrics.reconnected(0, 3);
        metrics.frame(0, 3, 100, "book", System.currentTimeMillis(), 1_000, 2_000, true);
        metrics.failure(0, 3, "onFailure", "okhttp", 503, "unavailable");

        metrics.startPhase("p2");

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();
        assertThat(snapshot.frames()).isZero();
        assertThat(snapshot.maxFrame()).isZero();
        assertThat(snapshot.maxCallbackContext().durationNs()).isZero();
        assertThat(snapshot.maxInterFrameGapNs()).isZero();
        assertThat(snapshot.generation()).isEqualTo(3);
        assertThat(snapshot.reconnectCount()).isEqualTo(1);
        assertThat(snapshot.history()).hasSize(1);
    }

    @Test
    void matchesPingAndPongByGenerationAndAllowsOnlyOneOutstandingPing() {
        BenchmarkMetrics metrics = metrics();
        Instant pingAt = Instant.parse("2026-10-13T10:00:00Z");
        metrics.publishGeneration(0, 4);

        assertThat(metrics.ping(0, 4, pingAt, 1_000)).isTrue();
        assertThat(metrics.ping(0, 4, pingAt.plusSeconds(1), 2_000)).isFalse();
        assertThat(metrics.pong(0, 3, pingAt.plusMillis(1).toEpochMilli(), 2_000)).isFalse();
        assertThat(metrics.pong(0, 4, pingAt.plusMillis(2).toEpochMilli(), 3_500)).isTrue();

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();
        assertThat(snapshot.lastPingAt()).isEqualTo(pingAt);
        assertThat(snapshot.lastPongAt()).isEqualTo(pingAt.plusMillis(2));
        assertThat(snapshot.lastPongRttMs()).isEqualTo(0.0025);
        assertThat(snapshot.maxPongRttMs()).isEqualTo(0.0025);
    }

    @Test
    void detectsPongTimeoutForCurrentGenerationOnly() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 6);
        metrics.ping(0, 6, Instant.now(), 1_000);

        assertThat(metrics.isPingTimedOut(0, 6, 25_000_000_999L, java.time.Duration.ofSeconds(25)))
                .isFalse();
        assertThat(metrics.isPingTimedOut(0, 6, 25_000_001_000L, java.time.Duration.ofSeconds(25)))
                .isTrue();
        assertThat(metrics.isPingTimedOut(0, 5, 25_000_001_000L, java.time.Duration.ofSeconds(25)))
                .isFalse();
    }

    @Test
    void cancelsPendingPingWhenSendFails() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 5);
        assertThat(metrics.ping(0, 5, Instant.now(), 1_000)).isTrue();

        metrics.cancelPing(0, 5);

        assertThat(metrics.ping(0, 5, Instant.now(), 2_000)).isTrue();
    }

    @Test
    void boundsStructuredHistoryAndRetainsLatestCompatibilityFields() {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 9);
        for (int index = 0; index < BenchmarkMetrics.HISTORY_CAPACITY + 5; index++) {
            metrics.failure(0, 9, "onFailure", "okhttp", 500 + index, "failure-" + index);
        }
        metrics.close(0, 9, "onClosed", "remote", 1001, "done");

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();
        assertThat(snapshot.history()).hasSize(BenchmarkMetrics.HISTORY_CAPACITY);
        assertThat(snapshot.history().getFirst().error()).isEqualTo("failure-6");
        assertThat(snapshot.history().getLast().callback()).isEqualTo("onClosed");
        assertThat(snapshot.failure()).isEqualTo("failure-68");
        assertThat(snapshot.close()).isEqualTo("1001:done");
    }

    private static BenchmarkMetrics metrics() {
        return metrics(new AtomicLong(System.nanoTime()));
    }

    private static BenchmarkMetrics metrics(AtomicLong nanoTime) {
        BenchmarkProperties properties = new BenchmarkProperties();
        properties.setConnections(1);
        properties.setQueueCapacity(10);
        return new BenchmarkMetrics(properties, new SimpleMeterRegistry(), nanoTime::get);
    }
}
