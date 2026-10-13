package com.example.polymarket.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.polymarket.config.BenchmarkProperties;
import com.example.polymarket.metrics.BenchmarkMetrics;
import com.example.polymarket.report.ReportWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BenchmarkServiceFaultInjectionTest {
    private final MockWebServer server = new MockWebServer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<WebSocket> serverSockets = new CopyOnWriteArrayList<>();
    private BenchmarkService service;

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.stop();
        }
        for (WebSocket socket : serverSockets) {
            socket.close(1000, "test complete");
        }
        server.shutdown();
    }

    @Test
    void classifies1013AndReconnectsWithInitialSubscriptionSchema() throws Exception {
        CountDownLatch firstSubscription = new CountDownLatch(1);
        CountDownLatch recoveredSubscription = new CountDownLatch(1);
        List<String> payloads = new CopyOnWriteArrayList<>();
        server.enqueue(webSocket(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (!"PING".equals(text)) {
                    payloads.add(text);
                    firstSubscription.countDown();
                    webSocket.close(1013, "slow consumer: send buffer full");
                }
            }
        }));
        server.enqueue(webSocket(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (!"PING".equals(text)) {
                    payloads.add(text);
                    recoveredSubscription.countDown();
                } else {
                    webSocket.send("PONG");
                }
            }
        }));

        BenchmarkMetrics metrics = startService(Duration.ofSeconds(5), Duration.ofMillis(20));

        assertThat(firstSubscription.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(recoveredSubscription.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(payloads).hasSizeGreaterThanOrEqualTo(2);
        assertInitialSubscription(payloads.get(0));
        assertInitialSubscription(payloads.get(1));
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> {
            var snapshot = metrics.snapshot(new int[] {0}).getFirst();
            assertThat(snapshot.reconnectCount()).isGreaterThanOrEqualTo(1);
            assertThat(snapshot.history())
                    .anySatisfy(event -> {
                        assertThat(event.generation()).isEqualTo(1);
                        assertThat(event.failureKind()).isEqualTo("slowConsumer");
                        assertThat(event.response()).isEqualTo(1013);
                    });
        });
        assertThat(RecoveryPolicy.cappedDelayMillis(1, true, Duration.ofMillis(1),
                        Duration.ofMillis(20), Duration.ofMillis(100)))
                .isGreaterThan(RecoveryPolicy.cappedDelayMillis(1, false, Duration.ofMillis(1),
                        Duration.ofMillis(20), Duration.ofMillis(100)));
    }

    @Test
    void missingPongCancelsSocketReconnectsAndRestoresInitialSubscription() throws Exception {
        CountDownLatch recoveredSubscription = new CountDownLatch(1);
        List<String> recoveredPayloads = new CopyOnWriteArrayList<>();
        server.enqueue(webSocket(new WebSocketListener() {}));
        server.enqueue(webSocket(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (!"PING".equals(text)) {
                    recoveredPayloads.add(text);
                    recoveredSubscription.countDown();
                } else {
                    webSocket.send("PONG");
                }
            }
        }));

        BenchmarkMetrics metrics = startService(Duration.ofMillis(15), Duration.ofMillis(1));

        assertThat(recoveredSubscription.await(2, TimeUnit.SECONDS)).isTrue();
        assertInitialSubscription(recoveredPayloads.getFirst());
        await().atMost(Duration.ofSeconds(1)).untilAsserted(() -> assertThat(metrics.snapshot(new int[] {0})
                .getFirst().history()).anySatisfy(event -> assertThat(event.failureKind()).isEqualTo("pongTimeout")));
    }

    @Test
    void oldGenerationCallbacksDoNotOverwriteCurrentGenerationState() {
        BenchmarkProperties properties = properties(Duration.ofSeconds(1), Duration.ofMillis(1));
        BenchmarkMetrics metrics = new BenchmarkMetrics(properties, new SimpleMeterRegistry());
        metrics.publishGeneration(0, 2);
        metrics.close(0, 2, "onClosed", "remote", 1000, "current close");
        metrics.failure(0, 2, "onFailure", "okhttp", "pongTimeout", null, "current failure");

        metrics.close(0, 1, "onClosed", "remote", 1013, "late old close");
        metrics.failure(0, 1, "onFailure", "okhttp", "slowConsumer", 1013, "late old failure");

        var snapshot = metrics.snapshot(new int[] {0}).getFirst();
        assertThat(snapshot.close()).isEqualTo("1000:current close");
        assertThat(snapshot.failure()).isEqualTo("pongTimeout:current failure");
        assertThat(snapshot.history()).extracting(BenchmarkMetrics.LifecycleEvent::generation)
                .containsExactly(2L, 2L, 1L, 1L);
    }

    private BenchmarkMetrics startService(Duration pongTimeout, Duration slowConsumerDelay) throws Exception {
        BenchmarkProperties properties = properties(pongTimeout, slowConsumerDelay);
        BenchmarkMetrics metrics = new BenchmarkMetrics(properties, new SimpleMeterRegistry());
        AssetIdLoader loader = mock(AssetIdLoader.class);
        when(loader.load(properties.getAssetsFile())).thenReturn(List.of("asset-1"));
        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .readTimeout(Duration.ZERO)
                .build();
        service = new BenchmarkService(properties, loader, mapper, metrics, mock(ReportWriter.class), client);
        service.run(null);
        return metrics;
    }

    private BenchmarkProperties properties(Duration pongTimeout, Duration slowConsumerDelay) {
        BenchmarkProperties properties = new BenchmarkProperties();
        properties.setUrl(server.url("/market").newBuilder().host("127.0.0.1").build().toString());
        properties.setPhaseSubscriptionCounts(List.of(1));
        properties.setPhaseDuration(Duration.ofSeconds(3));
        properties.setMaxRuntime(Duration.ofSeconds(3));
        properties.setConnections(1);
        properties.setConsumers(1);
        properties.setQueueCapacity(16);
        properties.setSubscriptionBatchSize(10);
        properties.setSubscriptionBatchInterval(Duration.ZERO);
        properties.setSubscriptionRecoveryPermitsPerSecond(1000);
        properties.setPingInterval(Duration.ofMillis(10));
        properties.setPongTimeout(pongTimeout);
        properties.setReconnectBaseDelay(Duration.ofMillis(1));
        properties.setSlowConsumerBaseDelay(slowConsumerDelay);
        properties.setReconnectMaxDelay(Duration.ofMillis(100));
        properties.setStableConnectionResetAfter(Duration.ofSeconds(10));
        return properties;
    }

    private MockResponse webSocket(WebSocketListener listener) {
        return new MockResponse().withWebSocketUpgrade(new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response) {
                serverSockets.add(webSocket);
                listener.onOpen(webSocket, response);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                listener.onMessage(webSocket, text);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                listener.onClosing(webSocket, code, reason);
                webSocket.close(code, reason);
            }
        });
    }

    private void assertInitialSubscription(String payload) throws Exception {
        JsonNode json = mapper.readTree(payload);
        assertThat(json.path("type").asText()).isEqualTo("market");
        assertThat(json.has("operation")).isFalse();
        assertThat(json.path("assets_ids").get(0).asText()).isEqualTo("asset-1");
        assertThat(json.path("initial_dump").asBoolean()).isFalse();
    }
}
