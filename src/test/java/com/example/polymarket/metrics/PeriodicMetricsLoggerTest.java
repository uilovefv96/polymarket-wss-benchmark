package com.example.polymarket.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PeriodicMetricsLoggerTest {
    @Test
    void calculatesWindowRatesUsing1024BytesPerKiB() {
        var rates = PeriodicMetricsLogger.windowRates(6, 3072, 2);

        assertThat(rates.framesPerSecond()).isEqualTo(3);
        assertThat(rates.bytesPerSecond()).isEqualTo(1536);
        assertThat(rates.kibibytesPerSecond()).isEqualTo(1.5);
        assertThat(rates.framesPerKiB()).isEqualTo(2);
    }

    @Test
    void reportsZeroFramesPerKiBWhenWindowHasNoBytes() {
        var rates = PeriodicMetricsLogger.windowRates(6, 0, 2);

        assertThat(rates.framesPerKiB()).isZero();
        assertThat(rates.kibibytesPerSecond()).isZero();
    }
}
