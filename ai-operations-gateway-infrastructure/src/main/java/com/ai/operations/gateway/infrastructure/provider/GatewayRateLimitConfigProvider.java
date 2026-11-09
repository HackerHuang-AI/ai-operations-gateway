package com.ai.operations.gateway.infrastructure.provider;

import com.ai.operations.gateway.application.governance.GatewayRateLimitPolicy;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimit;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 订阅 Nacos 限流配置，解析后交由限流策略服务校验并应用。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayRateLimitConfigProvider {

    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final GatewayRateLimitPolicy rateLimitPolicy;

    public GatewayRateLimitConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper,
                                          GatewayRateLimitPolicy rateLimitPolicy) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
        this.rateLimitPolicy = rateLimitPolicy;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, this::applyConfig);
    }

    private void applyConfig(String content) {
        if (content == null || content.isBlank()) {
            rateLimitPolicy.replace(null);
            log.info("[GatewayRateLimit] Reset to default rule");
            return;
        }
        try {
            rateLimitPolicy.replace(objectMapper.readValue(content, RateLimit.class));
            log.info("[GatewayRateLimit] Applied rate limit config");
        } catch (Exception exception) {
            log.error("[GatewayRateLimit] Ignoring invalid rate limit config and retaining the last valid snapshot", exception);
        }
    }
}
