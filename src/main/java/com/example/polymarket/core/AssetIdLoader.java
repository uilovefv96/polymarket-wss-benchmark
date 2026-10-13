package com.example.polymarket.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * 从 Spring {@link org.springframework.core.io.Resource} 加载资产 ID。
 *
 * <p>该组件不保存可变状态，可由多个线程安全复用；实际文件读取由调用线程同步完成。
 */
@Component
public class AssetIdLoader {
    private final ResourceLoader resources;
    private final ObjectMapper mapper;

    public AssetIdLoader(ResourceLoader resources, ObjectMapper mapper) {
        this.resources = resources;
        this.mapper = mapper;
    }

    public List<String> load(String location) throws IOException {
        var resource = resources.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("资产文件不存在: " + location);
        }

        String raw;
        try (var input = resource.getInputStream()) {
            raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // joining 一次性构造清洗结果，避免在大量 ID 场景中反复拼接字符串。
        String cleaned = Arrays.stream(raw.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .collect(Collectors.joining(","));
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("资产文件为空或仅含注释: " + location);
        }

        List<String> ids;
        if (cleaned.startsWith("[")) {
            ids = mapper.readValue(cleaned, new TypeReference<>() {});
        } else {
            ids = Arrays.stream(cleaned.split(","))
                    .map(String::trim)
                    .map(value -> value.replaceAll("^\"|\"$", ""))
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("资产文件未包含有效 ID: " + location);
        }
        return List.copyOf(ids);
    }
}
