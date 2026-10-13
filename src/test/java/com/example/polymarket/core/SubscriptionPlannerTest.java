package com.example.polymarket.core;
import com.example.polymarket.config.BenchmarkProperties.ShardingMode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
class SubscriptionPlannerTest {
 @Test void createsBoundedBatches(){assertThat(SubscriptionPlanner.batches(List.of("a","b","c","d","e"),2)).containsExactly(List.of("a","b"),List.of("c","d"),List.of("e"));}
 @Test void shardsRoundRobin(){assertThat(SubscriptionPlanner.shard(List.of("a","b","c","d","e"),2,ShardingMode.ROUND_ROBIN)).containsExactly(List.of("a","c","e"),List.of("b","d"));}
}
