package com.example.polymarket.metrics;

import com.example.polymarket.config.BenchmarkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/** 汇总连接、消息、队列、回调耗时和连接生命周期指标，并生成阶段快照。 */
public class BenchmarkMetrics {
    static final int HISTORY_CAPACITY = 64;

    private static final long[] LATENCY_BOUNDS = {
        1_000,
        2_000,
        5_000,
        10_000,
        20_000,
        50_000,
        100_000,
        250_000,
        500_000,
        1_000_000,
        2_000_000,
        5_000_000,
        10_000_000
    };

    public record FramePeak(Instant at, String type, int bytes) {
        private static final FramePeak EMPTY = new FramePeak(null, "", 0);
    }

    public record CallbackPeak(Instant at, String type, int bytes, long durationNs) {
        private static final CallbackPeak EMPTY = new CallbackPeak(null, "", 0, 0);
    }

    public record LifecycleEvent(
            Instant at,
            long generation,
            String callback,
            String source,
            Integer code,
            Integer response,
            String error) {}

    private record PendingPing(long generation, Instant at, long nanoTime) {}

    /** 单条连接的无锁/低竞争指标容器。 */
    public static final class Connection {
        public final LongAdder frames = new LongAdder();
        public final LongAdder bytes = new LongAdder();
        public final LongAdder bookFrames = new LongAdder();
        public final LongAdder bookBytes = new LongAdder();
        public final LongAdder enqueued = new LongAdder();
        public final LongAdder processed = new LongAdder();
        public final LongAdder dropped = new LongAdder();
        public final LongAdder latencyTotalNs = new LongAdder();
        public final LongAdder latencyCount = new LongAdder();
        public final AtomicLong generation = new AtomicLong();
        public final AtomicLong reconnectCount = new AtomicLong();
        public final AtomicLong lastFrameAtEpochMs = new AtomicLong();
        public final AtomicReference<FramePeak> maxFrame = new AtomicReference<>(FramePeak.EMPTY);
        public final AtomicReference<CallbackPeak> maxCallback =
                new AtomicReference<>(CallbackPeak.EMPTY);
        public final AtomicLong maxInterFrameGapNs = new AtomicLong();
        public final AtomicLong latencyMaxNs = new AtomicLong();
        public final AtomicReference<Instant> lastPingAt = new AtomicReference<>();
        public final AtomicLong lastPongAtEpochMs = new AtomicLong();
        public final AtomicLong lastPongRttNs = new AtomicLong();
        public final AtomicLong maxPongRttNs = new AtomicLong();
        public final AtomicInteger subscriptions = new AtomicInteger();
        public final AtomicReference<String> close = new AtomicReference<>("");
        public final AtomicReference<String> failure = new AtomicReference<>("");
        public final ConcurrentHashMap<String, LongAdder> eventTypes = new ConcurrentHashMap<>();
        public final AtomicLongArray latencyBuckets =
                new AtomicLongArray(LATENCY_BOUNDS.length + 1);

        private final AtomicLong lastFrameNanoTime = new AtomicLong();
        private final AtomicReference<PendingPing> pendingPing = new AtomicReference<>();
        private final Deque<LifecycleEvent> history = new ArrayDeque<>(HISTORY_CAPACITY);

        private void addHistory(LifecycleEvent event) {
            synchronized (history) {
                if (history.size() == HISTORY_CAPACITY) {
                    history.removeFirst();
                }
                history.addLast(event);
            }
        }

        private List<LifecycleEvent> historySnapshot() {
            synchronized (history) {
                return List.copyOf(history);
            }
        }
    }

    public record ConnectionSnapshot(
            int id,
            long generation,
            long reconnectCount,
            int subscriptions,
            long frames,
            long bytes,
            long bookFrames,
            long bookBytes,
            long enqueued,
            long processed,
            long dropped,
            int queueDepth,
            double queueUsage,
            long averageFrame,
            long maxFrame,
            Instant lastFrameAt,
            Instant maxFrameAt,
            FramePeak maxFrameContext,
            long maxInterFrameGapNs,
            Map<String, Long> eventTypes,
            double onMessageAverageUs,
            double onMessageP95Us,
            double onMessageP99Us,
            double onMessageMaxUs,
            CallbackPeak maxCallbackContext,
            Instant lastPingAt,
            Instant lastPongAt,
            double lastPongRttMs,
            double maxPongRttMs,
            String close,
            String failure,
            List<LifecycleEvent> history) {}

    public record PhaseSnapshot(
            String phase,
            Instant startedAt,
            Instant endedAt,
            List<ConnectionSnapshot> connections) {}

    private final Connection[] connections;
    private final AtomicIntegerArray queueDepths;
    private final int queueCapacity;
    private final List<PhaseSnapshot> phases =
            Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<String> phase = new AtomicReference<>("startup");
    private final AtomicReference<Instant> phaseStarted = new AtomicReference<>(Instant.now());

    public BenchmarkMetrics(BenchmarkProperties properties, MeterRegistry registry) {
        queueCapacity = properties.getQueueCapacity();
        connections = new Connection[properties.getConnections()];
        queueDepths = new AtomicIntegerArray(properties.getConnections());
        for (int index = 0; index < connections.length; index++) {
            connections[index] = new Connection();
        }
        registry.gauge("benchmark.queue.depth", this, BenchmarkMetrics::totalQueueDepth);
        registry.gauge("benchmark.frames.total", this, BenchmarkMetrics::totalFrames);
        registry.gauge("benchmark.dropped.total", this, BenchmarkMetrics::totalDropped);
    }

    public Connection connection(int id) {
        return connections[id];
    }

    public int size() {
        return connections.length;
    }

    public void publishGeneration(int id, long generation) {
        Connection connection = connections[id];
        connection.generation.set(generation);
        connection.lastFrameNanoTime.set(0);
        connection.pendingPing.set(null);
    }

    public boolean isCurrentGeneration(int id, long generation) {
        return connections[id].generation.get() == generation;
    }

    public void reconnected(int id, long generation) {
        Connection connection = connections[id];
        if (connection.generation.get() == generation) {
            connection.reconnectCount.incrementAndGet();
        }
    }

    public void frame(
            int id,
            long generation,
            int bytes,
            String type,
            long callbackStartedEpochMs,
            long callbackStartedNs,
            long callbackDurationNs,
            boolean queued) {
        Connection connection = connections[id];
        if (connection.generation.get() != generation) {
            return;
        }

        connection.frames.increment();
        connection.bytes.add(bytes);
        connection.lastFrameAtEpochMs.set(callbackStartedEpochMs);
        updateMaxFrame(connection.maxFrame, callbackStartedEpochMs, type, bytes);
        updateMaxCallback(
                connection.maxCallback,
                callbackStartedEpochMs,
                type,
                bytes,
                callbackDurationNs);
        long previousFrameNs = connection.lastFrameNanoTime.getAndSet(callbackStartedNs);
        if (previousFrameNs != 0 && callbackStartedNs >= previousFrameNs) {
            connection.maxInterFrameGapNs.accumulateAndGet(
                    callbackStartedNs - previousFrameNs, Math::max);
        }
        connection.eventTypes.computeIfAbsent(type, ignored -> new LongAdder()).increment();
        if ("book".equals(type)) {
            connection.bookFrames.increment();
            connection.bookBytes.add(bytes);
        } else if (!"pong".equals(type)) {
            if (queued) {
                connection.enqueued.increment();
            } else {
                connection.dropped.increment();
            }
        }
        connection.latencyTotalNs.add(callbackDurationNs);
        connection.latencyCount.increment();
        connection.latencyMaxNs.accumulateAndGet(callbackDurationNs, Math::max);
        int bucket = 0;
        while (bucket < LATENCY_BOUNDS.length && callbackDurationNs > LATENCY_BOUNDS[bucket]) {
            bucket++;
        }
        connection.latencyBuckets.incrementAndGet(bucket);
    }

    private static void updateMaxFrame(
            AtomicReference<FramePeak> reference,
            long callbackStartedEpochMs,
            String type,
            int bytes) {
        FramePeak current = reference.get();
        if (bytes <= current.bytes()) {
            return;
        }
        FramePeak candidate =
                new FramePeak(Instant.ofEpochMilli(callbackStartedEpochMs), type, bytes);
        while (bytes > current.bytes() && !reference.compareAndSet(current, candidate)) {
            current = reference.get();
        }
    }

    private static void updateMaxCallback(
            AtomicReference<CallbackPeak> reference,
            long callbackStartedEpochMs,
            String type,
            int bytes,
            long callbackDurationNs) {
        CallbackPeak current = reference.get();
        if (callbackDurationNs <= current.durationNs()) {
            return;
        }
        CallbackPeak candidate =
                new CallbackPeak(
                        Instant.ofEpochMilli(callbackStartedEpochMs),
                        type,
                        bytes,
                        callbackDurationNs);
        while (callbackDurationNs > current.durationNs()
                && !reference.compareAndSet(current, candidate)) {
            current = reference.get();
        }
    }

    public boolean ping(int id, long generation, Instant at, long nanoTime) {
        Connection connection = connections[id];
        if (connection.generation.get() != generation) {
            return false;
        }
        PendingPing pending = new PendingPing(generation, at, nanoTime);
        if (!connection.pendingPing.compareAndSet(null, pending)) {
            return false;
        }
        if (connection.generation.get() != generation) {
            connection.pendingPing.compareAndSet(pending, null);
            return false;
        }
        connection.lastPingAt.set(at);
        return true;
    }

    public void cancelPing(int id, long generation) {
        Connection connection = connections[id];
        PendingPing pending = connection.pendingPing.get();
        if (pending != null && pending.generation() == generation) {
            connection.pendingPing.compareAndSet(pending, null);
        }
    }

    public boolean pong(int id, long generation, long epochMs, long nanoTime) {
        Connection connection = connections[id];
        if (connection.generation.get() != generation) {
            return false;
        }
        PendingPing pending = connection.pendingPing.get();
        if (pending == null
                || pending.generation() != generation
                || !connection.pendingPing.compareAndSet(pending, null)) {
            return false;
        }
        long rtt = Math.max(0, nanoTime - pending.nanoTime());
        connection.lastPongAtEpochMs.set(epochMs);
        connection.lastPongRttNs.set(rtt);
        connection.maxPongRttNs.accumulateAndGet(rtt, Math::max);
        return true;
    }

    public void close(
            int id, long generation, String callback, String source, Integer code, String error) {
        Connection connection = connections[id];
        String latest = code == null ? String.valueOf(error) : code + ":" + String.valueOf(error);
        connection.close.set(latest);
        connection.addHistory(
                new LifecycleEvent(
                        Instant.now(), generation, callback, source, code, null, error));
    }

    public void failure(
            int id,
            long generation,
            String callback,
            String source,
            Integer response,
            Throwable error) {
        failure(
                id,
                generation,
                callback,
                source,
                response,
                error == null ? null : error.toString());
    }

    public void failure(
            int id,
            long generation,
            String callback,
            String source,
            Integer response,
            String error) {
        Connection connection = connections[id];
        connection.failure.set(String.valueOf(error));
        connection.addHistory(
                new LifecycleEvent(
                        Instant.now(), generation, callback, source, null, response, error));
    }

    public void processed(int id) {
        connections[id].processed.increment();
    }

    public void queueDepth(int id, int depth) {
        queueDepths.set(id, depth);
    }

    public void startPhase(String name) {
        for (Connection connection : connections) {
            connection.frames.reset();
            connection.bytes.reset();
            connection.bookFrames.reset();
            connection.bookBytes.reset();
            connection.enqueued.reset();
            connection.processed.reset();
            connection.dropped.reset();
            connection.latencyTotalNs.reset();
            connection.latencyCount.reset();
            connection.lastFrameAtEpochMs.set(0);
            connection.maxFrame.set(FramePeak.EMPTY);
            connection.maxCallback.set(CallbackPeak.EMPTY);
            connection.maxInterFrameGapNs.set(0);
            connection.lastFrameNanoTime.set(0);
            connection.latencyMaxNs.set(0);
            connection.eventTypes.clear();
            for (int index = 0; index < connection.latencyBuckets.length(); index++) {
                connection.latencyBuckets.set(index, 0);
            }
        }
        phase.set(name);
        phaseStarted.set(Instant.now());
    }

    public String currentPhase() {
        return phase.get();
    }

    public PhaseSnapshot finishPhase(int[] depths) {
        PhaseSnapshot snapshot =
                new PhaseSnapshot(phase.get(), phaseStarted.get(), Instant.now(), snapshot(depths));
        phases.add(snapshot);
        return snapshot;
    }

    public List<ConnectionSnapshot> snapshot(int[] depths) {
        List<ConnectionSnapshot> result = new ArrayList<>();
        for (int id = 0; id < connections.length; id++) {
            Connection connection = connections[id];
            long frames = connection.frames.sum();
            long bytes = connection.bytes.sum();
            long latencyCount = connection.latencyCount.sum();
            Map<String, Long> eventTypes = new TreeMap<>();
            connection.eventTypes.forEach(
                    (type, count) -> eventTypes.put(type, count.sum()));
            FramePeak maxFrame = connection.maxFrame.get();
            CallbackPeak maxCallback = connection.maxCallback.get();
            result.add(
                    new ConnectionSnapshot(
                            id,
                            connection.generation.get(),
                            connection.reconnectCount.get(),
                            connection.subscriptions.get(),
                            frames,
                            bytes,
                            connection.bookFrames.sum(),
                            connection.bookBytes.sum(),
                            connection.enqueued.sum(),
                            connection.processed.sum(),
                            connection.dropped.sum(),
                            depths[id],
                            queueCapacity == 0 ? 0 : (double) depths[id] / queueCapacity,
                            frames == 0 ? 0 : bytes / frames,
                            maxFrame.bytes(),
                            instantOrNull(connection.lastFrameAtEpochMs.get()),
                            maxFrame.at(),
                            maxFrame,
                            connection.maxInterFrameGapNs.get(),
                            eventTypes,
                            latencyCount == 0
                                    ? 0
                                    : connection.latencyTotalNs.sum() / 1000.0 / latencyCount,
                            percentile(connection, .95) / 1000.0,
                            percentile(connection, .99) / 1000.0,
                            connection.latencyMaxNs.get() / 1000.0,
                            maxCallback,
                            connection.lastPingAt.get(),
                            instantOrNull(connection.lastPongAtEpochMs.get()),
                            connection.lastPongRttNs.get() / 1_000_000.0,
                            connection.maxPongRttNs.get() / 1_000_000.0,
                            connection.close.get(),
                            connection.failure.get(),
                            connection.historySnapshot()));
        }
        return result;
    }

    private static Instant instantOrNull(long epochMs) {
        return epochMs == 0 ? null : Instant.ofEpochMilli(epochMs);
    }

    private long percentile(Connection connection, double percentile) {
        long total = connection.latencyCount.sum();
        if (total == 0) {
            return 0;
        }
        long target = (long) Math.ceil(total * percentile);
        long seen = 0;
        for (int index = 0; index < connection.latencyBuckets.length(); index++) {
            seen += connection.latencyBuckets.get(index);
            if (seen >= target) {
                return index < LATENCY_BOUNDS.length
                        ? LATENCY_BOUNDS[index]
                        : connection.latencyMaxNs.get();
            }
        }
        return connection.latencyMaxNs.get();
    }

    public List<PhaseSnapshot> phases() {
        synchronized (phases) {
            return List.copyOf(phases);
        }
    }

    public long totalFrames() {
        return Arrays.stream(connections).mapToLong(connection -> connection.frames.sum()).sum();
    }

    public long totalDropped() {
        return Arrays.stream(connections).mapToLong(connection -> connection.dropped.sum()).sum();
    }

    public int totalQueueDepth() {
        int total = 0;
        for (int index = 0; index < queueDepths.length(); index++) {
            total += queueDepths.get(index);
        }
        return total;
    }
}
