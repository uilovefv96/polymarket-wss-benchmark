package com.example.polymarket.report;

import com.example.polymarket.metrics.BenchmarkMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 将最终阶段指标写入 JSON 和 CSV 报告，并保留最近一次报告供接口查询。
 *
 * <p>写入方法使用同步锁保证同一时刻只生成一组文件；{@code latest} 使用 volatile 发布不可变报告映射，允许控制器线程无锁读取。
 */
@Component
public class ReportWriter {
    private final ObjectMapper mapper;
    private volatile Map<String, Object> latest = Map.of();

    public ReportWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public synchronized void writeFinal(
            List<BenchmarkMetrics.PhaseSnapshot> phases, Map<String, Object> status)
            throws IOException {
        Path directory = Path.of("reports");
        Files.createDirectories(directory);
        String timestamp =
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        latest =
                Map.of(
                        "generatedAt", Instant.now(),
                        "phases", phases,
                        "finalStatus", status);
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("benchmark-" + timestamp + ".json").toFile(), latest);

        StringBuilder csv =
                new StringBuilder(
                        "phase,started_at,ended_at,connection,subscriptions,frames,bytes,average_frame,max_frame,book_frames,book_bytes,enqueued,processed,dropped,queue_depth,queue_usage,on_message_avg_us,on_message_p95_us,on_message_p99_us,on_message_max_us,last_pong,close,failure,event_types\n");
        for (var phase : phases) {
            for (var connection : phase.connections()) {
                csv.append(quote(phase.phase()))
                        .append(',')
                        .append(phase.startedAt())
                        .append(',')
                        .append(phase.endedAt())
                        .append(',')
                        .append(connection.id())
                        .append(',')
                        .append(connection.subscriptions())
                        .append(',')
                        .append(connection.frames())
                        .append(',')
                        .append(connection.bytes())
                        .append(',')
                        .append(connection.averageFrame())
                        .append(',')
                        .append(connection.maxFrame())
                        .append(',')
                        .append(connection.bookFrames())
                        .append(',')
                        .append(connection.bookBytes())
                        .append(',')
                        .append(connection.enqueued())
                        .append(',')
                        .append(connection.processed())
                        .append(',')
                        .append(connection.dropped())
                        .append(',')
                        .append(connection.queueDepth())
                        .append(',')
                        .append(connection.queueUsage())
                        .append(',')
                        .append(connection.onMessageAverageUs())
                        .append(',')
                        .append(connection.onMessageP95Us())
                        .append(',')
                        .append(connection.onMessageP99Us())
                        .append(',')
                        .append(connection.onMessageMaxUs())
                        .append(',')
                        .append(connection.lastPong())
                        .append(',')
                        .append(quote(connection.close()))
                        .append(',')
                        .append(quote(connection.failure()))
                        .append(',')
                        .append(quote(connection.eventTypes().toString()))
                        .append('\n');
            }
        }
        Files.writeString(
                directory.resolve("benchmark-" + timestamp + ".csv"),
                csv,
                StandardCharsets.UTF_8);
    }

    public Map<String, Object> latest() {
        return latest;
    }

    private static String quote(Object value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
}
