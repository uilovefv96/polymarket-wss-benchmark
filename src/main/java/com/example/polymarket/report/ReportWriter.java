package com.example.polymarket.report;

import com.example.polymarket.metrics.BenchmarkMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
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

/** 将最终阶段指标写入 JSON 和 CSV 报告，并保留最近一次报告供接口查询。 */
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

        String csv = buildCsv(phases);
        Files.writeString(
                directory.resolve("benchmark-" + timestamp + ".csv"),
                csv,
                StandardCharsets.UTF_8);
    }

    String buildCsv(List<BenchmarkMetrics.PhaseSnapshot> phases) throws JsonProcessingException {
        StringBuilder csv =
                new StringBuilder(
                        "phase,started_at,ended_at,connection,generation,reconnect_count,subscriptions,frames,bytes,kibibytes,frames_per_kib,frames_100ms,bytes_100ms,kibibytes_100ms,frames_per_kib_100ms,frames_1s,bytes_1s,kibibytes_1s,frames_per_kib_1s,average_frame,max_frame,last_frame_at,max_frame_at,max_frame_context,max_inter_frame_gap_ns,book_frames,book_bytes,enqueued,processed,dropped,queue_depth,queue_usage,on_message_avg_us,on_message_p95_us,on_message_p99_us,on_message_max_us,max_callback_context,last_ping_at,last_pong_at,last_pong_rtt_ms,max_pong_rtt_ms,close,failure,event_types,history\n");
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
                        .append(connection.generation())
                        .append(',')
                        .append(connection.reconnectCount())
                        .append(',')
                        .append(connection.subscriptions())
                        .append(',')
                        .append(connection.frames())
                        .append(',')
                        .append(connection.bytes())
                        .append(',')
                        .append(connection.kibibytes())
                        .append(',')
                        .append(connection.framesPerKiB())
                        .append(',')
                        .append(connection.frames100ms())
                        .append(',')
                        .append(connection.bytes100ms())
                        .append(',')
                        .append(connection.kibibytes100ms())
                        .append(',')
                        .append(connection.framesPerKiB100ms())
                        .append(',')
                        .append(connection.frames1s())
                        .append(',')
                        .append(connection.bytes1s())
                        .append(',')
                        .append(connection.kibibytes1s())
                        .append(',')
                        .append(connection.framesPerKiB1s())
                        .append(',')
                        .append(connection.averageFrame())
                        .append(',')
                        .append(connection.maxFrame())
                        .append(',')
                        .append(connection.lastFrameAt())
                        .append(',')
                        .append(connection.maxFrameAt())
                        .append(',')
                        .append(jsonCell(connection.maxFrameContext()))
                        .append(',')
                        .append(connection.maxInterFrameGapNs())
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
                        .append(jsonCell(connection.maxCallbackContext()))
                        .append(',')
                        .append(connection.lastPingAt())
                        .append(',')
                        .append(connection.lastPongAt())
                        .append(',')
                        .append(connection.lastPongRttMs())
                        .append(',')
                        .append(connection.maxPongRttMs())
                        .append(',')
                        .append(quote(connection.close()))
                        .append(',')
                        .append(quote(connection.failure()))
                        .append(',')
                        .append(jsonCell(connection.eventTypes()))
                        .append(',')
                        .append(jsonCell(connection.history()))
                        .append('\n');
            }
        }
        return csv.toString();
    }

    public Map<String, Object> latest() {
        return latest;
    }

    private String jsonCell(Object value) throws JsonProcessingException {
        return quote(mapper.writeValueAsString(value));
    }

    private static String quote(Object value) {
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }
}
