package com.example.polymarket.metrics;
import com.example.polymarket.config.BenchmarkProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class BenchmarkMetricsTest {
 @Test void capturesPhaseSnapshot(){var p=new BenchmarkProperties();p.setConnections(1);p.setQueueCapacity(10);var m=new BenchmarkMetrics(p,new SimpleMeterRegistry());m.startPhase("p1");m.connection(0).subscriptions.set(5);m.frame(0,100,"book",2_000,true);m.frame(0,50,"price_change",10_000,true);m.processed(0);var s=m.finishPhase(new int[]{1});assertThat(s.phase()).isEqualTo("p1");assertThat(s.connections().getFirst().frames()).isEqualTo(2);assertThat(s.connections().getFirst().bookFrames()).isEqualTo(1);assertThat(s.connections().getFirst().queueUsage()).isEqualTo(.1);assertThat(s.connections().getFirst().eventTypes()).containsEntry("price_change",1L);}
}
