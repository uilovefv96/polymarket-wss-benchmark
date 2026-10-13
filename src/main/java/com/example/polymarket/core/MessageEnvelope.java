package com.example.polymarket.core;

/** 从 WebSocket 回调线程移交给业务消费线程的不可变消息。 */
public record MessageEnvelope(
        int connectionId, String eventType, String payload, int bytes) {}
