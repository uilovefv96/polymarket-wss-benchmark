package com.example.polymarket.metrics;

import com.example.polymarket.config.BenchmarkProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

/**
 * 汇总连接、消息、队列和回调耗时指标，并生成阶段快照。
 *
 * <p>WebSocket 回调线程、业务消费线程、定时日志线程会并发读写本类。热路径计数使用 {@link LongAdder}，状态值使用原子类；
 * 阶段列表通过同步包装并在读取时复制，快照允许反映采集时刻附近的最终一致值。
 */
public class BenchmarkMetrics {
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
        public final AtomicLong maxFrame = new AtomicLong();
        public final AtomicLong latencyMaxNs = new AtomicLong();
        public final AtomicLong lastPongEpochMs = new AtomicLong();
        public final AtomicInteger subscriptions = new AtomicInteger();
        public final AtomicReference<String> close = new AtomicReference<>("");
        public final AtomicReference<String> failure = new AtomicReference<>("");
        public final ConcurrentHashMap<String, LongAdder> eventTypes = new ConcurrentHashMap<>();
        public final AtomicLongArray latencyBuckets =
                new AtomicLongArray(LATENCY_BOUNDS.length + 1);
    }

    public record ConnectionSnapshot(
            int id,
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
            Map<String, Long> eventTypes,
            double onMessageAverageUs,
            double onMessageP95Us,
            double onMessageP99Us,
            double onMessageMaxUs,
            Instant lastPong,
            String close,
            String failure) {}

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

    public void frame(int id, int bytes, String type, long latencyNs, boolean queued) {
        Connection connection = connections[id];
        connection.frames.increment();
        connection.bytes.add(bytes);
        connection.maxFrame.accumulateAndGet(bytes, Math::max);
        connection.eventTypes.computeIfAbsent(type, ignored -> new LongAdder()).increment();
        if ("book".equals(type)) {
            connection.bookFrames.increment();
            connection.bookBytes.add(bytes);
        } else if (!"pong".equals(type)) {
            // offer 失败代表有界队列已满：不阻塞回调线程，直接记为丢弃。
            if (queued) {
                connection.enqueued.increment();
            } else {
                connection.dropped.increment();
            }
        }
        connection.latencyTotalNs.add(latencyNs);
        connection.latencyCount.increment();
        connection.latencyMaxNs.accumulateAndGet(latencyNs, Math::max);
        int bucket = 0;
        while (bucket < LATENCY_BOUNDS.length && latencyNs > LATENCY_BOUNDS[bucket]) {
            bucket++;
        }
        connection.latencyBuckets.incrementAndGet(bucket);
    }

    public void processed(int id) {
        connections[id].processed.increment();
    }

    public void queueDepth(int id, int depth) {
        queueDepths.set(id, depth);
    }

    public void pong(int id) {
        connections[id].lastPongEpochMs.set(System.currentTimeMillis());
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
            connection.maxFrame.set(0);
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
            long latencyCount = connection.latencyCount.sum();
            Map<String, Long> eventTypes = new TreeMap<>();
            connection.eventTypes.forEach(
                    (type, count) -> eventTypes.put(type, count.sum()));
            long pong = connection.lastPongEpochMs.get();
            result.add(
                    new ConnectionSnapshot(
                            id,
                            connection.subscriptions.get(),
                            frames,
                            connection.bytes.sum(),
                            connection.bookFrames.sum(),
                            connection.bookBytes.sum(),
                            connection.enqueued.sum(),
                            connection.processed.sum(),
                            connection.dropped.sum(),
                            depths[id],
                            queueCapacity == 0 ? 0 : (double) depths[id] / queueCapacity,
                            frames == 0 ? 0 : connection.bytes.sum() / frames,
                            connection.maxFrame.get(),
                            eventTypes,
                            latencyCount == 0
                                    ? 0
                                    : connection.latencyTotalNs.sum() / 1000.0 / latencyCount,
                            percentile(connection, .95) / 1000.0,
                            percentile(connection, .99) / 1000.0,
                            connection.latencyMaxNs.get() / 1000.0,
                            pong == 0 ? null : Instant.ofEpochMilli(pong),
                            connection.close.get(),
                            connection.failure.get()));
        }
        return result;
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
