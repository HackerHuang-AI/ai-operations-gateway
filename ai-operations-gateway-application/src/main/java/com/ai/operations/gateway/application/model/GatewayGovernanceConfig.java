package com.ai.operations.gateway.application.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * 定义网关治理配置的数据结构。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
public final class GatewayGovernanceConfig {

    private GatewayGovernanceConfig() {
    }

    public record Routes(
            /** 无需会话鉴权的请求路径列表。 */
            List<String> authWhitelist,
            /** 网关路由规则列表。 */
            List<Route> routes) {
    }

    public record Route(
            /** 路由唯一标识。 */
            String id,
            /** 路由转发目标地址。 */
            String uri,
            /** 路由匹配条件列表。 */
            List<Predicate> predicates,
            /** 路由过滤器配置列表。 */
            List<Filter> filters) {
    }

    public record Predicate(
            /** 路由断言名称。 */
            String name,
            /** 路由断言参数。 */
            Map<String, String> args) {
    }

    public record Filter(
            /** 路由过滤器名称。 */
            String name,
            /** 路由过滤器参数。 */
            Map<String, String> args) {
    }

    public record RateLimit(
            /** 未单独配置路由时使用的限流规则。 */
            @JsonProperty("default") RateLimitRule defaultRule,
            /** 按路由标识配置的限流规则。 */
            Map<String, RateLimitRule> routes) {
    }

    public record RateLimitRule(
            /** 是否启用限流。 */
            Boolean enabled,
            /** 令牌桶每秒补充的令牌数。 */
            Integer replenishRate,
            /** 令牌桶最大令牌数。 */
            Integer burstCapacity,
            /** 单次请求消耗的令牌数。 */
            Integer requestedTokens) {
    }

    public record CircuitBreaker(
            /** 未单独配置实例时使用的熔断规则。 */
            @JsonProperty("default") CircuitBreakerRule defaultRule,
            /** 按实例标识配置的熔断规则。 */
            Map<String, CircuitBreakerRule> instances) {
    }

    public record CircuitBreakerRule(
            /** 熔断器滑动窗口中的调用数。 */
            Integer slidingWindowSize,
            /** 触发失败率计算所需的最小调用数。 */
            Integer minimumNumberOfCalls,
            /** 触发熔断的失败率阈值，单位：百分比。 */
            Float failureRateThreshold,
            /** 熔断打开后的等待时长，单位：秒。 */
            Integer waitDurationInOpenStateSeconds,
            /** 半开状态允许的最大探测调用数。 */
            Integer permittedNumberOfCallsInHalfOpenState) {
    }
}
