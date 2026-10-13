package com.example.polymarket.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将待订阅资产转换为 Polymarket WebSocket 协议载荷。
 *
 * <p>本类无共享状态，可并发调用。每次连接建立后的首批消息必须使用 market 初始订阅协议，后续批次则使用
 * subscribe 动态追加协议。
 */
public final class SubscriptionPayloadPlanner {
    public record PlannedPayload(
            boolean initial, List<String> assetIds, Map<String, Object> payload) {}

    private SubscriptionPayloadPlanner() {}

    public static List<PlannedPayload> plan(
            List<String> pendingIds, int batchSize, int level, boolean initialRequired) {
        List<PlannedPayload> result = new ArrayList<>();
        boolean initial = initialRequired;
        for (List<String> batch : SubscriptionPlanner.batches(pendingIds, batchSize)) {
            Map<String, Object> payload = new LinkedHashMap<>();
            if (initial) {
                // 新连接的第一批资产负责建立 market 会话，不能使用动态追加格式。
                payload.put("type", "market");
                payload.put("assets_ids", batch);
                payload.put("initial_dump", false);
            } else {
                // 同一连接已完成首次订阅后，新增资产通过 subscribe 增量加入。
                payload.put("operation", "subscribe");
                payload.put("assets_ids", batch);
            }
            payload.put("level", level);
            payload.put("custom_feature_enabled", false);
            result.add(new PlannedPayload(initial, batch, Map.copyOf(payload)));
            initial = false;
        }
        return List.copyOf(result);
    }
}
