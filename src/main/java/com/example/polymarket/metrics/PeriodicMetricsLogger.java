package com.example.polymarket.metrics;

import com.example.polymarket.config.BenchmarkProperties;
import com.example.polymarket.core.BenchmarkService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期输出各连接的吞吐、积压、时序和连接生命周期指标。 */
@Component
public class PeriodicMetricsLogger {
    private static final Logger log = LoggerFactory.getLogger(PeriodicMetricsLogger.class);

    private record Previous(long frames, long bytes, long sampledAtNs) {}

    record WindowRates(
            double framesPerSecond,
            double bytesPerSecond,
            double kibibytesPerSecond,
            double framesPerKiB) {}

    private final BenchmarkService service;
    private final BenchmarkProperties config;
    private final Map<Integer, Previous> previous = new HashMap<>();

    public PeriodicMetricsLogger(BenchmarkService service, BenchmarkProperties config) {
        this.service = service;
        this.config = config;
    }

    @Scheduled(fixedRate = 10000)
    public void logSnapshot() {
        long sampledAtNs = System.nanoTime();
        var status = service.status();
        @SuppressWarnings("unchecked")
        var connections =
                (List<BenchmarkMetrics.ConnectionSnapshot>) status.get("connections");
        for (var connection : connections) {
            Previous old = previous.get(connection.id());
            double elapsedSeconds =
                    old == null ? 0 : (sampledAtNs - old.sampledAtNs()) / 1_000_000_000.0;
            long previousFrames = old == null || connection.frames() < old.frames() ? 0 : old.frames();
            long previousBytes = old == null || connection.bytes() < old.bytes() ? 0 : old.bytes();
            WindowRates rates =
                    windowRates(
                            connection.frames() - previousFrames,
                            connection.bytes() - previousBytes,
                            elapsedSeconds);
            log.info(
                    "phase={} conn={} generation={} reconnects={} subscriptions={} frames/s={} bytes/s={} KB/s={} frames/KB={} avgFrame={} maxFrame={} lastFrameAt={} maxFrameAt={} maxGapMs={} maxCallback={} bookFrames={} bookBytes={} events={} enqueued={} processed={} dropped={} queue={}/{} ({}%) onMessageUs avg/p95/p99/max={}/{}/{}/{} lastPingAt={} lastPongAt={} pongRttMs last/max={}/{} historySize={} close={} failure={}",
                    status.get("phase"),
                    connection.id(),
                    connection.generation(),
                    connection.reconnectCount(),
                    connection.subscriptions(),
                    String.format("%.2f", rates.framesPerSecond()),
                    String.format("%.2f", rates.bytesPerSecond()),
                    String.format("%.2f", rates.kibibytesPerSecond()),
                    String.format("%.2f", rates.framesPerKiB()),
                    connection.averageFrame(),
                    connection.maxFrame(),
                    connection.lastFrameAt(),
                    connection.maxFrameAt(),
                    String.format("%.3f", connection.maxInterFrameGapNs() / 1_000_000.0),
                    connection.maxCallbackContext(),
                    connection.bookFrames(),
                    connection.bookBytes(),
                    connection.eventTypes(),
                    connection.enqueued(),
                    connection.processed(),
                    connection.dropped(),
                    connection.queueDepth(),
                    config.getQueueCapacity(),
                    String.format("%.1f", connection.queueUsage() * 100),
                    String.format("%.2f", connection.onMessageAverageUs()),
                    String.format("%.2f", connection.onMessageP95Us()),
                    String.format("%.2f", connection.onMessageP99Us()),
                    String.format("%.2f", connection.onMessageMaxUs()),
                    connection.lastPingAt(),
                    connection.lastPongAt(),
                    String.format("%.3f", connection.lastPongRttMs()),
                    String.format("%.3f", connection.maxPongRttMs()),
                    connection.history().size(),
                    connection.close(),
                    connection.failure());
            previous.put(
                    connection.id(),
                    new Previous(connection.frames(), connection.bytes(), sampledAtNs));
        }
    }

    static WindowRates windowRates(long frames, long bytes, double elapsedSeconds) {
        if (elapsedSeconds <= 0) {
            return new WindowRates(0, 0, 0, 0);
        }
        double kibibytes = bytes / 1024.0;
        return new WindowRates(
                frames / elapsedSeconds,
                bytes / elapsedSeconds,
                kibibytes / elapsedSeconds,
                kibibytes == 0 ? 0 : frames / kibibytes);
    }
}
