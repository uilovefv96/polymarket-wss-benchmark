package com.example.polymarket;

import com.example.polymarket.config.BenchmarkProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot 启动入口，启用配置绑定与定时指标日志；运行期工作线程由各组件自行管理。 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(BenchmarkProperties.class)
public class BenchmarkApplication {
    public static void main(String[] args) {
        SpringApplication.run(BenchmarkApplication.class, args);
    }
}
