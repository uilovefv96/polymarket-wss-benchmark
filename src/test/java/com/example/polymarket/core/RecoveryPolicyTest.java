package com.example.polymarket.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RecoveryPolicyTest {
    @Test
    void slowConsumerUsesLongerBaseAndBothPathsAreCapped() {
        Duration normal = Duration.ofMillis(500);
        Duration slow = Duration.ofSeconds(5);
        Duration maximum = Duration.ofSeconds(30);

        assertThat(RecoveryPolicy.cappedDelayMillis(1, false, normal, slow, maximum)).isEqualTo(500);
        assertThat(RecoveryPolicy.cappedDelayMillis(1, true, normal, slow, maximum)).isEqualTo(5_000);
        assertThat(RecoveryPolicy.cappedDelayMillis(20, false, normal, slow, maximum)).isEqualTo(30_000);
        assertThat(RecoveryPolicy.cappedDelayMillis(20, true, normal, slow, maximum)).isEqualTo(30_000);
    }

    @Test
    void onlyCurrentOpenGenerationMayResetAttempts() {
        assertThat(RecoveryPolicy.mayResetAttempts(7, 7, true)).isTrue();
        assertThat(RecoveryPolicy.mayResetAttempts(7, 8, true)).isFalse();
        assertThat(RecoveryPolicy.mayResetAttempts(7, 7, false)).isFalse();
    }
}
