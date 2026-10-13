package com.example.polymarket.core;

import com.example.polymarket.config.BenchmarkProperties.ShardingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责纯内存的订阅批次切分与连接分片规划。
 *
 * <p>本类无共享状态，所有方法均可被并发调用；返回值使用不可变列表，避免规划结果被异步发送线程修改。
 */
public final class SubscriptionPlanner {
    private SubscriptionPlanner() {}

    public static List<List<String>> batches(List<String> ids, int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("batch size 必须大于 0");
        }
        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < ids.size(); index += size) {
            result.add(List.copyOf(ids.subList(index, Math.min(index + size, ids.size()))));
        }
        return result;
    }

    public static List<List<String>> shard(
            List<String> ids, int connections, ShardingMode mode) {
        if (connections <= 0) {
            throw new IllegalArgumentException("connections 必须大于 0");
        }

        List<List<String>> result = new ArrayList<>();
        for (int index = 0; index < connections; index++) {
            result.add(new ArrayList<>());
        }

        if (mode == ShardingMode.ROUND_ROBIN) {
            for (int index = 0; index < ids.size(); index++) {
                result.get(index % connections).add(ids.get(index));
            }
        } else {
            int shardSize = (ids.size() + connections - 1) / connections;
            for (int index = 0; index < connections; index++) {
                int from = Math.min(index * shardSize, ids.size());
                int to = Math.min(from + shardSize, ids.size());
                result.get(index).addAll(ids.subList(from, to));
            }
        }
        return result.stream().map(List::copyOf).toList();
    }
}
