package com.ai.operations.gateway.application.provider;

import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加载并提供网关调用链治理配置。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayCallChainConfigProvider {

    private static final int DEFAULT_MAX_CALL_CHAIN_HOPS = 64;
    private static final int DEFAULT_MAX_CALL_CHAIN_LENGTH = 2048;
    private static final int MIN_MAX_CALL_CHAIN_LENGTH = 64;

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final AtomicInteger maxCallChainHops = new AtomicInteger(DEFAULT_MAX_CALL_CHAIN_HOPS);
    private final AtomicInteger maxCallChainLength = new AtomicInteger(DEFAULT_MAX_CALL_CHAIN_LENGTH);

    public GatewayCallChainConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, this::applyConfig);
    }

    public int maxCallChainHops() {
        return maxCallChainHops.get();
    }

    public int maxCallChainLength() {
        return maxCallChainLength.get();
    }

    private void applyConfig(String content) {
        try {
            if (content == null || content.isBlank()) {
                maxCallChainHops.set(DEFAULT_MAX_CALL_CHAIN_HOPS);
                maxCallChainLength.set(DEFAULT_MAX_CALL_CHAIN_LENGTH);
                log.info("[GatewayCallChain] Reset maxCallChainHops={}, maxCallChainLength={}",
                        DEFAULT_MAX_CALL_CHAIN_HOPS, DEFAULT_MAX_CALL_CHAIN_LENGTH);
                return;
            }
            JsonNode config = objectMapper.readTree(content);
            int configuredMaxHops = config.path("maxCallChainHops").asInt(0);
            int configuredMaxLength = config.has("maxCallChainLength")
                    ? config.path("maxCallChainLength").asInt(0) : DEFAULT_MAX_CALL_CHAIN_LENGTH;
            if (!config.isObject() || configuredMaxHops < 2 || configuredMaxLength < MIN_MAX_CALL_CHAIN_LENGTH) {
                throw new IllegalArgumentException("maxCallChainHops must be at least 2 and maxCallChainLength at least 64");
            }
            maxCallChainHops.set(configuredMaxHops);
            maxCallChainLength.set(configuredMaxLength);
            log.info("[GatewayCallChain] Applied maxCallChainHops={}, maxCallChainLength={}",
                    configuredMaxHops, configuredMaxLength);
        } catch (Exception exception) {
            log.error("[GatewayCallChain] Ignoring invalid config and retaining maxCallChainHops={}, maxCallChainLength={}",
                    maxCallChainHops.get(), maxCallChainLength.get(), exception);
        }
    }
}

