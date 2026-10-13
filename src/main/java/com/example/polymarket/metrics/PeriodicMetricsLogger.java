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

/**
 * 定期输出各连接的吞吐、积压、丢弃与回调耗时。
 *
 * <p>方法由 Spring 单线程调度器周期调用，{@code previous} 仅在该调度线程访问；底层指标快照可与网络和消费线程并发生成。
 */
@Component
public class PeriodicMetricsLogger {
    private static final Logger log = LoggerFactory.getLogger(PeriodicMetricsLogger.class);
    private static final double PERIOD_SECONDS = 10.0;

    private final BenchmarkService service;
    private final BenchmarkProperties config;
    private final Map<Integer, long[]> previous = new HashMap<>();

    public PeriodicMetricsLogger(BenchmarkService service, BenchmarkProperties config) {
        this.service = service;
        this.config = config;
    }

    @Scheduled(fixedRate = 10000)
    public void logSnapshot() {
        var status = service.status();
        @SuppressWarnings("unchecked")
        var connections =
                (List<BenchmarkMetrics.ConnectionSnapshot>) status.get("connections");
        for (var connection : connections) {
            long[] old = previous.getOrDefault(connection.id(), new long[] {0, 0});
            // 阶段切换会清零计数器，此时速率应从零基线重新计算。
            if (connection.frames() < old[0] || connection.bytes() < old[1]) {
                old = new long[] {0, 0};
            }
            log.info(
                    "phase={} conn={} subscriptions={} frames/s={} bytes/s={} avgFrame={} maxFrame={} bookFrames={} bookBytes={} events={} enqueued={} processed={} dropped={} queue={}/{} ({}%) onMessageUs avg/p95/p99/max={}/{}/{}/{} lastPong={} close={} failure={}",
                    status.get("phase"),
                    connection.id(),
                    connection.subscriptions(),
                    (connection.frames() - old[0]) / PERIOD_SECONDS,
                    (connection.bytes() - old[1]) / PERIOD_SECONDS,
                    connection.averageFrame(),
                    connection.maxFrame(),
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
                    connection.lastPong(),
                    connection.close(),
                    connection.failure());
            previous.put(
                    connection.id(), new long[] {connection.frames(), connection.bytes()});
        }
    }
}
