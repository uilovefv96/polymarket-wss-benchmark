package com.example.polymarket.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * WebSocket 压测的外部配置映射。
 *
 * <p>属性由 Spring 在应用启动阶段绑定；压测启动后仅并发读取，不应在运行期间修改。
 */
@ConfigurationProperties("benchmark")
public class BenchmarkProperties {
    private String url = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
    private String assetsFile = "classpath:assets_ids.txt";
    private List<Integer> phaseSubscriptionCounts =
            new ArrayList<>(List.of(100, 500, 1000));
    private Duration phaseDuration = Duration.ofMinutes(5);
    private int subscriptionBatchSize = 100;
    private Duration subscriptionBatchInterval = Duration.ofMillis(250);
    private int connections = 1;
    private ShardingMode shardingMode = ShardingMode.ROUND_ROBIN;
    private int queueCapacity = 10000;
    private int consumers = 4;
    private Duration simulatedProcessingDelay = Duration.ZERO;
    private boolean autoReconnect = true;
    private Duration maxRuntime = Duration.ofMinutes(20);
    private int level = 2;
    private Duration pingInterval = Duration.ofSeconds(10);

    public enum ShardingMode {
        ROUND_ROBIN,
        CONTIGUOUS
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAssetsFile() {
        return assetsFile;
    }

    public void setAssetsFile(String assetsFile) {
        this.assetsFile = assetsFile;
    }

    public List<Integer> getPhaseSubscriptionCounts() {
        return phaseSubscriptionCounts;
    }

    public void setPhaseSubscriptionCounts(List<Integer> phaseSubscriptionCounts) {
        this.phaseSubscriptionCounts = phaseSubscriptionCounts;
    }

    public Duration getPhaseDuration() {
        return phaseDuration;
    }

    public void setPhaseDuration(Duration phaseDuration) {
        this.phaseDuration = phaseDuration;
    }

    public int getSubscriptionBatchSize() {
        return subscriptionBatchSize;
    }

    public void setSubscriptionBatchSize(int subscriptionBatchSize) {
        this.subscriptionBatchSize = subscriptionBatchSize;
    }

    public Duration getSubscriptionBatchInterval() {
        return subscriptionBatchInterval;
    }

    public void setSubscriptionBatchInterval(Duration subscriptionBatchInterval) {
        this.subscriptionBatchInterval = subscriptionBatchInterval;
    }

    public int getConnections() {
        return connections;
    }

    public void setConnections(int connections) {
        this.connections = connections;
    }

    public ShardingMode getShardingMode() {
        return shardingMode;
    }

    public void setShardingMode(ShardingMode shardingMode) {
        this.shardingMode = shardingMode;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getConsumers() {
        return consumers;
    }

    public void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    public Duration getSimulatedProcessingDelay() {
        return simulatedProcessingDelay;
    }

    public void setSimulatedProcessingDelay(Duration simulatedProcessingDelay) {
        this.simulatedProcessingDelay = simulatedProcessingDelay;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    public Duration getMaxRuntime() {
        return maxRuntime;
    }

    public void setMaxRuntime(Duration maxRuntime) {
        this.maxRuntime = maxRuntime;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public Duration getPingInterval() {
        return pingInterval;
    }

    public void setPingInterval(Duration pingInterval) {
        this.pingInterval = pingInterval;
    }
}
