package com.ai.operations.gateway.infrastructure.provider;

import com.ai.operations.gateway.application.governance.CallChainPolicy;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订阅 Nacos 调用链治理配置，解析后交由调用链策略服务校验并应用。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayCallChainConfigProvider {

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final CallChainPolicy callChainPolicy;

    public GatewayCallChainConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper,
                                          CallChainPolicy callChainPolicy) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
        this.callChainPolicy = callChainPolicy;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_CALL_CHAIN, this::applyConfig);
    }

    private void applyConfig(String content) {
        try {
            if (content == null || content.isBlank()) {
                callChainPolicy.replace(null, null);
                log.info("[GatewayCallChain] Reset maxCallChainHops={}, maxCallChainLength={}",
                        CallChainPolicy.DEFAULT_MAX_CALL_CHAIN_HOPS, CallChainPolicy.DEFAULT_MAX_CALL_CHAIN_LENGTH);
                return;
            }
            JsonNode config = objectMapper.readTree(content);
            int configuredMaxHops = config.path("maxCallChainHops").asInt(0);
            int configuredMaxLength = config.has("maxCallChainLength")
                    ? config.path("maxCallChainLength").asInt(0) : CallChainPolicy.DEFAULT_MAX_CALL_CHAIN_LENGTH;
            if (!config.isObject()) {
                throw new IllegalArgumentException("call chain config must be a json object");
            }
            callChainPolicy.replace(configuredMaxHops, configuredMaxLength);
            log.info("[GatewayCallChain] Applied maxCallChainHops={}, maxCallChainLength={}",
                    configuredMaxHops, configuredMaxLength);
        } catch (Exception exception) {
            log.error("[GatewayCallChain] Ignoring invalid config and retaining maxCallChainHops={}, maxCallChainLength={}",
                    callChainPolicy.maxCallChainHops(), callChainPolicy.maxCallChainLength(), exception);
        }
    }
}
