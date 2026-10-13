package com.example.polymarket.web;

import com.example.polymarket.core.BenchmarkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供压测当前状态和已完成阶段报告的只读 HTTP 接口；请求线程仅获取并发安全快照。 */
@RestController
@RequestMapping("/api")
public class BenchmarkController {
    private final BenchmarkService service;

    public BenchmarkController(BenchmarkService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public Object status() {
        return service.status();
    }

    @GetMapping("/report")
    public Object report() {
        return service.report();
    }
}
