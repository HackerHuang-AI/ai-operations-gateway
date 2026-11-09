package com.ai.operations.gateway.application.governance;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有并提供下游扩展请求头治理查询，配置由加载方更新，本服务负责校验与归一化。
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
@Component
public class DownstreamHeadersPolicy {

    private static final String LEGACY_SESSION_ID_HEADER = "X-Session-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final Set<String> SUPPORTED_HEADERS = Set.of(SESSION_ID_HEADER, "X-Forwarded-For");
    private static final Map<String, Set<String>> DEFAULT_HEADERS =
            Map.of("ai-base", Set.of("X-Internal-Session-Id", "X-Forwarded-For"));

    private final AtomicReference<Map<String, Set<String>>> applicationHeaders =
            new AtomicReference<>(DEFAULT_HEADERS);

    public boolean shouldRemove(String applicationName, String headerName) {
        return !applicationHeaders.get().getOrDefault(applicationName, Set.of()).contains(headerName);
    }

    /**
     * 校验并应用新的下游请求头配置；config 为 null 表示重置为默认配置。
     *
     * @throws IllegalArgumentException 配置非法时抛出，调用方应保留旧快照
     */
    public void replace(Map<String, List<String>> config) {
        if (config == null) {
            applicationHeaders.set(DEFAULT_HEADERS);
            return;
        }
        Map<String, Set<String>> parsed = new HashMap<>();
        config.forEach((applicationName, headers) -> {
            if (applicationName == null || !applicationName.matches("[A-Za-z0-9._-]+") || headers == null) {
                throw new IllegalArgumentException("invalid downstream header config");
            }
            Set<String> configuredHeaders = headers.stream()
                    .map(header -> LEGACY_SESSION_ID_HEADER.equals(header) ? SESSION_ID_HEADER : header)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (!SUPPORTED_HEADERS.containsAll(configuredHeaders)) {
                throw new IllegalArgumentException("unsupported downstream header");
            }
            parsed.put(applicationName, configuredHeaders);
        });
        applicationHeaders.set(Map.copyOf(parsed));
    }
}
