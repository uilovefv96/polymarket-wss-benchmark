package com.example.polymarket.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.polymarket.config.BenchmarkProperties;
import com.example.polymarket.metrics.BenchmarkMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportWriterTest {
    @Test
    void writesCumulativeKiBFieldsToJsonAndCsv() throws Exception {
        BenchmarkMetrics metrics = metrics();
        metrics.publishGeneration(0, 1);
        metrics.frame(0, 1, 2048, "book", System.currentTimeMillis(), 1_000, 2_000, true);
        var connection = metrics.snapshot(new int[] {0}).getFirst();
        var phase =
                new BenchmarkMetrics.PhaseSnapshot(
                        "p1",
                        Instant.parse("2026-10-14T00:00:00Z"),
                        Instant.parse("2026-10-14T00:01:00Z"),
                        List.of(connection));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReportWriter writer = new ReportWriter(mapper);

        String json = mapper.writeValueAsString(phase);
        String csv = writer.buildCsv(List.of(phase));

        assertThat(json).contains("\"kibibytes\":2.0", "\"framesPerKiB\":0.5");
        assertThat(csv)
                .startsWith("phase,started_at,ended_at,connection,generation,reconnect_count,subscriptions,frames,bytes,kibibytes,frames_per_kib,")
                .contains(",1,2048,2.0,0.5,");
    }

    private static BenchmarkMetrics metrics() {
        BenchmarkProperties properties = new BenchmarkProperties();
        properties.setConnections(1);
        properties.setQueueCapacity(10);
        return new BenchmarkMetrics(properties, new SimpleMeterRegistry());
    }
}
