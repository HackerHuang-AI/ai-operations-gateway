package com.ai.operations.gateway.application.provider;

import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 加载并提供下游扩展请求头治理配置。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayDownstreamHeadersConfigProvider {

    private static final String LEGACY_SESSION_ID_HEADER = "X-Session-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final Set<String> SUPPORTED_HEADERS = Set.of(SESSION_ID_HEADER, "X-Forwarded-For");
    private static final Map<String, Set<String>> DEFAULT_HEADERS =
            Map.of("ai-operations", Set.of("X-Internal-Session-Id", "X-Forwarded-For"));

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Map<String, Set<String>>> applicationHeaders =
            new AtomicReference<>(DEFAULT_HEADERS);

    public GatewayDownstreamHeadersConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_DOWNSTREAM_HEADERS, this::applyConfig);
    }

    public boolean shouldRemove(String applicationName, String headerName) {
        return !applicationHeaders.get().getOrDefault(applicationName, Set.of()).contains(headerName);
    }

    private void applyConfig(String content) {
        if (content == null || content.isBlank()) {
            applicationHeaders.set(DEFAULT_HEADERS);
            return;
        }
        try {
            Map<String, List<String>> config = objectMapper.readValue(content, new TypeReference<>() { });
            Map<String, Set<String>> parsed = new java.util.HashMap<>();
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
            log.info("[GatewayDownstreamHeaders] Applied config for {} applications", parsed.size());
        } catch (Exception exception) {
            log.error("[GatewayDownstreamHeaders] Ignoring invalid config and retaining the last valid snapshot", exception);
        }
    }
}
