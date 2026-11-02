package com.ai.operations.gateway.application.provider;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimit;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.infrastructure.enums.NacosDataIdEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 加载并提供网关限流治理配置。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
public class GatewayRateLimitConfigProvider {

    private static final RateLimitRule DEFAULT_RULE = new RateLimitRule(true, 100, 200, 1);
    private final NacosConfig nacosConfig;
    private final ObjectMapper objectMapper;
    private final AtomicReference<RateLimitSnapshot> snapshot =
            new AtomicReference<>(new RateLimitSnapshot(DEFAULT_RULE, Map.of()));

    public GatewayRateLimitConfigProvider(NacosConfig nacosConfig, ObjectMapper objectMapper) {
        this.nacosConfig = nacosConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void registerConfigHandlers() {
        nacosConfig.registerConfigHandler(NacosDataIdEnum.AI_GATEWAY_RATE_LIMIT, this::applyConfig);
    }

    public RateLimitRule ruleFor(String routeId) {
        RateLimitSnapshot current = snapshot.get();
        return current.routes().getOrDefault(routeId, current.defaultRule());
    }

    private void applyConfig(String content) {
        if (content == null || content.isBlank()) {
            snapshot.set(new RateLimitSnapshot(DEFAULT_RULE, Map.of()));
            return;
        }
        try {
            RateLimit config = objectMapper.readValue(content, RateLimit.class);
            RateLimitRule defaultRule = merge(DEFAULT_RULE, config.defaultRule());
            Map<String, RateLimitRule> routeRules = new HashMap<>();
            if (config.routes() != null) {
                config.routes().forEach((routeId, rule) -> {
                    validateRouteId(routeId);
                    routeRules.put(routeId, merge(defaultRule, rule));
                });
            }
            snapshot.set(new RateLimitSnapshot(defaultRule, Map.copyOf(routeRules)));
            log.info("[GatewayRateLimit] Applied config for {} routes", routeRules.size());
        } catch (Exception exception) {
            log.error("[GatewayRateLimit] Ignoring invalid rate limit config and retaining the last valid snapshot", exception);
        }
    }

    private RateLimitRule merge(RateLimitRule base, RateLimitRule override) {
        RateLimitRule merged = new RateLimitRule(
                override != null && override.enabled() != null ? override.enabled() : base.enabled(),
                override != null && override.replenishRate() != null ? override.replenishRate() : base.replenishRate(),
                override != null && override.burstCapacity() != null ? override.burstCapacity() : base.burstCapacity(),
                override != null && override.requestedTokens() != null ? override.requestedTokens() : base.requestedTokens());
        if (merged.replenishRate() < 1 || merged.burstCapacity() < 1 || merged.requestedTokens() < 1
                || merged.requestedTokens() > merged.burstCapacity()
                || 2L * merged.burstCapacity() < merged.replenishRate()) {
            throw new IllegalArgumentException("invalid rate limit rule");
        }
        return merged;
    }

    private void validateRouteId(String routeId) {
        if (routeId == null || !routeId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid route id: " + routeId);
        }
    }

    private record RateLimitSnapshot(RateLimitRule defaultRule, Map<String, RateLimitRule> routes) {
    }
}
