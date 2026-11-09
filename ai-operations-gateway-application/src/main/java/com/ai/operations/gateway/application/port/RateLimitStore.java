package com.ai.operations.gateway.application.port;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import reactor.core.publisher.Mono;

/**
 * 限流存储端口：按路由与客户端维度执行令牌桶限流判定。
 *
 * <p>实现方负责存储键组织、Lua 脚本与原子性等存储细节；
 * 调用方只面向"是否放行"的治理语义，异常语义由调用方决定（fail-open / fail-close）。</p>
 *
 * @author HUANGcong
 * @since 2026-11-09
 */
public interface RateLimitStore {

    /**
     * 尝试为指定路由与客户端消费令牌。
     *
     * @param routeId  路由 ID（非空）
     * @param clientIp 客户端 IP，未知时传 "unknown"
     * @param rule     生效的限流规则
     * @return true=放行，false=拒绝
     */
    Mono<Boolean> tryAcquire(String routeId, String clientIp, RateLimitRule rule);
}
