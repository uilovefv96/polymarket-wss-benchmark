package com.example.polymarket.core;

import com.example.polymarket.config.BenchmarkProperties;
import com.example.polymarket.metrics.BenchmarkMetrics;
import com.example.polymarket.report.ReportWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import okio.Utf8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

/**
 * 编排 WebSocket 连接、分阶段订阅、消息移交、指标采集和最终报告。
 *
 * <p>OkHttp 回调线程只做轻量分类、计数和非阻塞入队；订阅发送、阶段推进和业务消费分别运行在独立线程池。
 * 每条连接的复合状态由对应 {@link ConnectionState} 对象锁保护，跨连接的运行标志与重连槽位使用原子变量协调。
 */
@Service
public class BenchmarkService implements ApplicationRunner, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    private final BenchmarkProperties config;
    private final AssetIdLoader loader;
    private final ObjectMapper mapper;
    private final BenchmarkMetrics metrics;
    private final ReportWriter reports;
    private final OkHttpClient client = new OkHttpClient.Builder().readTimeout(Duration.ZERO).build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> new Thread(r, "benchmark-scheduler"));
    private final ExecutorService phaseExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "benchmark-phase"));
    private final ExecutorService subscriptionExecutor;
    private final ExecutorService consumers;
    // 每条连接独享有界队列；offer 满时丢弃消息并计数，绝不反压 OkHttp 回调线程。
    private final ArrayBlockingQueue<MessageEnvelope>[] queues;
    // 每个元素自身同时充当锁，保护 socket/open/syncing/submitted/generation 的一致性。
    private final ConnectionState[] connectionStates;
    private final AtomicIntegerArray reconnectAttempts;
    // 同一连接仅允许一个失败 socket 占用重连槽，避免 onClosed/onFailure/PING 竞态重复调度。
    private final AtomicReferenceArray<WebSocket> reconnectScheduledFor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean acceptingInbound = new AtomicBoolean();

    private volatile List<String> assets = List.of();
    private volatile int currentTarget;

    @SuppressWarnings("unchecked")
    public BenchmarkService(
            BenchmarkProperties config,
            AssetIdLoader loader,
            ObjectMapper mapper,
            BenchmarkMetrics metrics,
            ReportWriter reports) {
        this.config = config;
        this.loader = loader;
        this.mapper = mapper;
        this.metrics = metrics;
        this.reports = reports;
        this.queues = new ArrayBlockingQueue[config.getConnections()];
        this.connectionStates = new ConnectionState[config.getConnections()];
        this.reconnectAttempts = new AtomicIntegerArray(config.getConnections());
        this.reconnectScheduledFor = new AtomicReferenceArray<>(config.getConnections());
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new ArrayBlockingQueue<>(config.getQueueCapacity());
            connectionStates[i] = new ConnectionState();
        }
        consumers = Executors.newFixedThreadPool(config.getConsumers(), r -> new Thread(r, "business-consumer"));
        subscriptionExecutor = Executors.newFixedThreadPool(config.getConnections(),
                r -> new Thread(r, "subscription-sender"));
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        assets = loader.load(config.getAssetsFile());
        int maximum = maximumTarget();
        if (maximum > assets.size()) {
            throw new IllegalStateException("阶段订阅数 " + maximum + " 超过资产 ID 数量 " + assets.size());
        }
        start();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        acceptingInbound.set(true);
        for (int i = 0; i < config.getConsumers(); i++) {
            consumers.submit(this::consume);
        }
        for (int i = 0; i < connectionStates.length; i++) {
            connect(i);
        }
        scheduler.scheduleAtFixedRate(this::pingAll, config.getPingInterval().toMillis(),
                config.getPingInterval().toMillis(), TimeUnit.MILLISECONDS);
        phaseExecutor.execute(this::runPhases);
    }

    private void connect(int id) {
        if (!running.get()) {
            return;
        }
        ConnectionState state = connectionStates[id];
        long generation;
        synchronized (state) {
            // generation 是连接纪元；旧 Listener 即使迟到回调，也不能覆盖或关闭新 socket。
            generation = ++state.generation;
            metrics.publishGeneration(id, generation);
            state.open = false;
            state.socket = null;
            state.submitted.clear();
            state.syncing = false;
            metrics.connection(id).subscriptions.set(0);
        }
        Request request = new Request.Builder().url(config.getUrl()).build();
        WebSocket socket = client.newWebSocket(request, new Listener(id, generation));
        synchronized (state) {
            if (state.generation == generation && state.socket == null) {
                state.socket = socket;
            }
        }
    }

    private void runPhases() {
        long deadline = System.nanoTime() + config.getMaxRuntime().toNanos();
        try {
            int number = 1;
            int previousTarget = 0;
            for (int target : config.getPhaseSubscriptionCounts()) {
                if (!running.get() || System.nanoTime() >= deadline) {
                    break;
                }
                if (target < previousTarget) {
                    throw new IllegalStateException("阶段订阅数量必须非递减");
                }
                currentTarget = target;
                metrics.startPhase("p" + number++);
                for (int id = 0; id < connectionStates.length; id++) {
                    requestSubscriptionSync(id);
                }
                previousTarget = target;
                long wait = Math.min(config.getPhaseDuration().toNanos(), Math.max(0, deadline - System.nanoTime()));
                TimeUnit.NANOSECONDS.sleep(wait);
                metrics.finishPhase(queueDepths());
            }
            reports.writeFinal(metrics.phases(), status());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("阶段执行失败", e);
        } finally {
            stop();
        }
    }

    private void requestSubscriptionSync(int id) {
        ConnectionState state = connectionStates[id];
        synchronized (state) {
            if (!running.get() || !state.open || state.socket == null || state.syncing) {
                return;
            }
            // syncing 是每连接的单飞标志，阶段切换和重连不会并发发送重复订阅。
            state.syncing = true;
        }
        subscriptionExecutor.execute(() -> syncSubscriptions(id));
    }

    private void syncSubscriptions(int id) {
        ConnectionState state = connectionStates[id];
        try {
            while (running.get()) {
                WebSocket socket;
                long generation;
                List<String> pending;
                boolean initialRequired;
                synchronized (state) {
                    if (!state.open || state.socket == null) {
                        return;
                    }
                    socket = state.socket;
                    generation = state.generation;
                    Set<String> desired = new LinkedHashSet<>(shardsForTarget(currentTarget).get(id));
                    pending = desired.stream()
                            .filter(asset -> !state.submitted.contains(asset))
                            .toList();
                    // 重连后 submitted 会清空，因此第一批恢复订阅仍使用 initial 协议。
                    initialRequired = state.submitted.isEmpty();
                    if (pending.isEmpty()) {
                        return;
                    }
                }

                for (var planned : SubscriptionPayloadPlanner.plan(pending, config.getSubscriptionBatchSize(),
                        config.getLevel(), initialRequired)) {
                    synchronized (state) {
                        if (!state.open
                                || state.socket != socket
                                || state.generation != generation) {
                            return;
                        }
                    }
                    String payload = mapper.writeValueAsString(planned.payload());
                    boolean sent = socket.send(payload);
                    long queueSize = socket.queueSize();
                    if (!sent) {
                        String failure = "订阅发送失败: generation=" + generation + ", queueSize=" + queueSize;
                        metrics.failure(id, generation, "syncSubscriptions", "subscription", null, failure);
                        log.warn("连接 {} {}", id, failure);
                        synchronized (state) {
                            if (state.generation == generation && state.socket == socket) {
                                state.open = false;
                            }
                        }
                        socket.cancel();
                        reconnect(id, socket);
                        return;
                    }
                    synchronized (state) {
                        if (state.generation != generation || state.socket != socket) {
                            return;
                        }
                        state.submitted.addAll(planned.assetIds());
                        metrics.connection(id).subscriptions.set(state.submitted.size());
                    }
                    log.info("连接 {} generation={} 发送 {} 订阅批次 {}，queueSize={}", id, generation,
                            planned.initial() ? "initial" : "dynamic", planned.assetIds().size(), queueSize);
                    if (!config.getSubscriptionBatchInterval().isZero()) {
                        TimeUnit.MILLISECONDS.sleep(config.getSubscriptionBatchInterval().toMillis());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            WebSocket socket;
            long generation;
            synchronized (state) {
                socket = state.socket;
                generation = state.generation;
                state.open = false;
            }
            metrics.failure(id, generation, "syncSubscriptions", "subscription", null, e);
            if (socket != null) {
                socket.cancel();
                reconnect(id, socket);
            }
        } finally {
            boolean retry;
            synchronized (state) {
                state.syncing = false;
                retry = state.open && state.submitted.size() < shardsForTarget(currentTarget).get(id).size();
            }
            if (retry) {
                requestSubscriptionSync(id);
            }
        }
    }

    private int maximumTarget() {
        return config.getPhaseSubscriptionCounts().stream().mapToInt(Integer::intValue).max().orElseThrow();
    }

    private List<List<String>> shardsForTarget(int target) {
        List<List<String>> stable = SubscriptionPlanner.shard(assets.subList(0, maximumTarget()),
                config.getConnections(), config.getShardingMode());
        Set<String> selected = new HashSet<>(assets.subList(0, target));
        return stable.stream().map(shard -> shard.stream().filter(selected::contains).toList()).toList();
    }

    private void consume() {
        while (running.get() || Arrays.stream(queues).anyMatch(queue -> !queue.isEmpty())) {
            boolean found = false;
            for (var queue : queues) {
                MessageEnvelope message = queue.poll();
                if (message != null) {
                    found = true;
                    if (!config.getSimulatedProcessingDelay().isZero()) {
                        LockSupport.parkNanos(config.getSimulatedProcessingDelay().toNanos());
                    }
                    metrics.processed(message.connectionId());
                }
            }
            if (!found) {
                LockSupport.parkNanos(100_000);
            }
        }
    }

    private void pingAll() {
        if (!running.get()) {
            return;
        }
        for (int id = 0; id < connectionStates.length; id++) {
            ConnectionState state = connectionStates[id];
            WebSocket socket;
            long generation;
            synchronized (state) {
                socket = state.open ? state.socket : null;
                generation = state.generation;
            }
            if (socket == null) {
                continue;
            }
            Instant at = Instant.now();
            if (!metrics.ping(id, generation, at, System.nanoTime())) {
                continue;
            }
            if (!socket.send("PING")) {
                metrics.cancelPing(id, generation);
                String failure = "PING 发送失败, queueSize=" + socket.queueSize();
                metrics.failure(id, generation, "pingAll", "ping", null, failure);
                socket.cancel();
                reconnect(id, socket);
            }
        }
    }

    private void reconnect(int id, WebSocket failedSocket) {
        // 多种失败回调可能指向同一 socket；CAS 确保只安排一次指数退避重连。
        if (!running.get()
                || !config.isAutoReconnect()
                || !reconnectScheduledFor.compareAndSet(id, null, failedSocket)) {
            return;
        }
        metrics.reconnected(id, metrics.connection(id).generation.get());
        int attempt = reconnectAttempts.incrementAndGet(id);
        long base = Math.min(30_000L, 500L << (Math.min(attempt, 6) - 1));
        long delay = ThreadLocalRandom.current().nextLong(Math.max(1, base / 2), base + 1);
        log.warn("连接 {} 将在 {} ms 后执行第 {} 次重连", id, delay, attempt);
        scheduler.schedule(() -> {
            reconnectScheduledFor.set(id, null);
            connect(id);
        }, delay, TimeUnit.MILLISECONDS);
    }

    public Map<String, Object> status() {
        int[] depths = queueDepths();
        for (int i = 0; i < depths.length; i++) {
            metrics.queueDepth(i, depths[i]);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("running", running.get());
        result.put("phase", metrics.currentPhase());
        result.put("targetSubscriptions", currentTarget);
        result.put("connections", metrics.snapshot(depths));
        return result;
    }

    public List<BenchmarkMetrics.PhaseSnapshot> report() {
        return metrics.phases();
    }

    private int[] queueDepths() {
        int[] depths = new int[queues.length];
        for (int i = 0; i < depths.length; i++) {
            depths[i] = queues[i].size();
        }
        return depths;
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        acceptingInbound.set(false);
        scheduler.shutdownNow();
        subscriptionExecutor.shutdownNow();

        List<CountDownLatch> closeLatches = new ArrayList<>();
        for (int id = 0; id < connectionStates.length; id++) {
            ConnectionState state = connectionStates[id];
            synchronized (state) {
                state.open = false;
                if (state.socket != null) {
                    state.closeLatch = new CountDownLatch(1);
                    closeLatches.add(state.closeLatch);
                    metrics.close(
                            id,
                            state.generation,
                            "stop",
                            "local",
                            1000,
                            "benchmark complete");
                    state.socket.close(1000, "benchmark complete");
                }
            }
        }
        awaitSocketCloses(closeLatches, Duration.ofSeconds(1));

        consumers.shutdown();
        awaitTermination(consumers, Duration.ofSeconds(5));
        if (!consumers.isTerminated()) {
            consumers.shutdownNow();
        }
        phaseExecutor.shutdownNow();
        client.dispatcher().executorService().shutdown();
        awaitTermination(client.dispatcher().executorService(), Duration.ofSeconds(1));
        client.connectionPool().evictAll();
    }

    private void awaitSocketCloses(List<CountDownLatch> latches, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (CountDownLatch latch : latches) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                latch.await(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private final class Listener extends WebSocketListener {
        private final int id;
        private final long generation;

        private Listener(int id, long generation) {
            this.id = id;
            this.generation = generation;
        }

        @Override
        public void onOpen(WebSocket socket, Response response) {
            ConnectionState state = connectionStates[id];
            synchronized (state) {
                if (state.generation != generation) {
                    metrics.close(id, generation, "onOpen", "local", 1000, "superseded");
                    socket.close(1000, "superseded");
                    return;
                }
                state.socket = socket;
                state.open = true;
                state.submitted.clear();
                metrics.connection(id).subscriptions.set(0);
            }
            reconnectAttempts.set(id, 0);
            reconnectScheduledFor.set(id, null);
            // 不在重连成功后清空历史 failure；否则阶段报告会丢失导致本次重连的根因。
            log.info(
                    "连接 {} generation={} 已建立，开始同步目标订阅 {}",
                    id,
                    generation,
                    currentTarget);
            requestSubscriptionSync(id);
        }

        @Override
        public void onMessage(WebSocket socket, String text) {
            long startedNs = System.nanoTime();
            long startedEpochMs = System.currentTimeMillis();
            if (!acceptingInbound.get() || !metrics.isCurrentGeneration(id, generation)) {
                return;
            }
            int bytes = Math.toIntExact(Utf8.size(text));
            if ("PONG".equals(text)) {
                metrics.pong(id, generation, System.currentTimeMillis(), System.nanoTime());
                metrics.frame(
                        id,
                        generation,
                        bytes,
                        "pong",
                        startedEpochMs,
                        startedNs,
                        System.nanoTime() - startedNs,
                        false);
                return;
            }
            String type = EventTypeScanner.scan(text);
            // book 只计吞吐，不进入业务队列；其他事件仅尝试 offer，保证 onMessage 永不等待消费者。
            boolean queued =
                    "book".equals(type)
                            || queues[id].offer(new MessageEnvelope(id, type, text, bytes));
            metrics.frame(
                    id,
                    generation,
                    bytes,
                    type,
                    startedEpochMs,
                    startedNs,
                    System.nanoTime() - startedNs,
                    queued);
        }

        @Override
        public void onMessage(WebSocket socket, ByteString bytes) {
            long startedNs = System.nanoTime();
            long startedEpochMs = System.currentTimeMillis();
            if (!acceptingInbound.get() || !metrics.isCurrentGeneration(id, generation)) {
                return;
            }
            String text = bytes.utf8();
            String type = EventTypeScanner.scan(text);
            boolean queued =
                    "book".equals(type)
                            || queues[id].offer(
                                    new MessageEnvelope(id, type, text, bytes.size()));
            metrics.frame(
                    id,
                    generation,
                    bytes.size(),
                    type,
                    startedEpochMs,
                    startedNs,
                    System.nanoTime() - startedNs,
                    queued);
        }

        @Override
        public void onClosing(WebSocket socket, int code, String reason) {
            metrics.close(id, generation, "onClosing", "remote", code, reason);
            log.warn(
                    "连接 {} generation={} 正在关闭，code={} reason={}",
                    id,
                    generation,
                    code,
                    reason);
            socket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket socket, int code, String reason) {
            signalClose();
            metrics.close(id, generation, "onClosed", "remote", code, reason);
            if (!markClosedIfCurrent(socket)) {
                return;
            }
            log.warn(
                    "连接 {} generation={} 已关闭，code={} reason={}",
                    id,
                    generation,
                    code,
                    reason);
            reconnect(id, socket);
        }

        @Override
        public void onFailure(WebSocket socket, Throwable error, Response response) {
            signalClose();
            metrics.failure(
                    id,
                    generation,
                    "onFailure",
                    "okhttp",
                    response == null ? null : response.code(),
                    error);
            if (!markClosedIfCurrent(socket)) {
                return;
            }
            log.warn(
                    "连接 {} generation={} 失败，responseCode={} error={}",
                    id,
                    generation,
                    response == null ? null : response.code(),
                    error.toString(),
                    error);
            reconnect(id, socket);
        }

        private void signalClose() {
            ConnectionState state = connectionStates[id];
            synchronized (state) {
                if (state.generation == generation && state.closeLatch != null) {
                    state.closeLatch.countDown();
                }
            }
        }

        private boolean markClosedIfCurrent(WebSocket socket) {
            ConnectionState state = connectionStates[id];
            synchronized (state) {
                if (state.generation != generation || state.socket != socket) {
                    return false;
                }
                state.open = false;
                return true;
            }
        }
    }

    /**
     * 单条逻辑连接的复合状态。所有字段均在该实例监视器内访问；generation 用于隔离旧连接的迟到回调。
     */
    private static final class ConnectionState {
        private long generation;
        private WebSocket socket;
        private boolean open;
        private boolean syncing;
        private CountDownLatch closeLatch;
        private final Set<String> submitted = new LinkedHashSet<>();
    }
}
