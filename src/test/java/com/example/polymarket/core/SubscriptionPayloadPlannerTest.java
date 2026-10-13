package com.example.polymarket.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionPayloadPlannerTest {
    @Test
    void firstBatchIsInitialAndRemainingBatchesAreDynamic() {
        var planned = SubscriptionPayloadPlanner.plan(List.of("a", "b", "c"), 2, 3, true);

        assertThat(planned).hasSize(2);
        assertThat(planned.get(0).initial()).isTrue();
        assertThat(planned.get(0).payload())
                .containsEntry("type", "market")
                .containsEntry("assets_ids", List.of("a", "b"))
                .containsEntry("initial_dump", false)
                .containsEntry("level", 3)
                .containsEntry("custom_feature_enabled", false)
                .doesNotContainKey("operation");
        assertThat(planned.get(1).initial()).isFalse();
        assertThat(planned.get(1).payload())
                .containsEntry("operation", "subscribe")
                .containsEntry("assets_ids", List.of("c"))
                .containsEntry("level", 3)
                .containsEntry("custom_feature_enabled", false)
                .doesNotContainKeys("type", "initial_dump");
    }

    @Test
    void phaseIncrementStartsWithDynamicPayload() {
        var planned = SubscriptionPayloadPlanner.plan(List.of("d", "e"), 100, 2, false);
        assertThat(planned).singleElement().satisfies(item -> {
            assertThat(item.initial()).isFalse();
            assertThat(item.payload()).containsEntry("operation", "subscribe")
                    .doesNotContainKeys("type", "initial_dump");
        });
    }
}
