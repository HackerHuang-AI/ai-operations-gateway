package com.ai.operations.gateway.application.governance;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimit;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有并提供限流规则查询，规则内容由配置加载方更新，本服务负责合并与合法性校验。
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
@Component
public class GatewayRateLimitPolicy {

    private static final RateLimitRule DEFAULT_RULE = new RateLimitRule(true, 100, 200, 1);

    private final AtomicReference<RateLimitSnapshot> snapshot =
            new AtomicReference<>(new RateLimitSnapshot(DEFAULT_RULE, Map.of()));

    public RateLimitRule ruleFor(String routeId) {
        RateLimitSnapshot current = snapshot.get();
        return current.routes().getOrDefault(routeId, current.defaultRule());
    }

    /**
     * 校验并应用新的限流配置；config 为 null 表示重置为默认规则。
     *
     * @throws IllegalArgumentException 配置非法时抛出，调用方应保留旧快照
     */
    public void replace(RateLimit config) {
        if (config == null) {
            snapshot.set(new RateLimitSnapshot(DEFAULT_RULE, Map.of()));
            return;
        }
        RateLimitRule defaultRule = merge(DEFAULT_RULE, config.defaultRule());
        Map<String, RateLimitRule> routeRules = new HashMap<>();
        if (config.routes() != null) {
            config.routes().forEach((routeId, rule) -> {
                validateRouteId(routeId);
                routeRules.put(routeId, merge(defaultRule, rule));
            });
        }
        snapshot.set(new RateLimitSnapshot(defaultRule, Map.copyOf(routeRules)));
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
