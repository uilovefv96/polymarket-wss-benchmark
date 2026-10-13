package com.example.polymarket.config;

import com.example.polymarket.metrics.BenchmarkMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 创建压测运行期基础组件；Bean 由 Spring 单例管理并供多个工作线程共享。 */
@Configuration
public class RuntimeConfiguration {
    @Bean
    BenchmarkMetrics benchmarkMetrics(
            BenchmarkProperties properties, MeterRegistry registry) {
        return new BenchmarkMetrics(properties, registry);
    }
}
