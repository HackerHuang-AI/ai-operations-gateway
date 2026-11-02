package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.provider.GatewayRateLimitConfigProvider;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
/**
 * 依据动态规则对网关请求实施限流。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(0) // 数值越小越先执行，在链路追踪、访问日志和鉴权之后执行限流。
public class NacosRateLimitGlobalFilter implements GlobalFilter {

    private final GatewayRateLimitConfigProvider configProvider;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> redisScript;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public NacosRateLimitGlobalFilter(GatewayRateLimitConfigProvider configProvider,
                                      ReactiveStringRedisTemplate redisTemplate,
                                      @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> redisScript,
                                      GatewayErrorResponseWriter errorResponseWriter) {
        this.configProvider = configProvider;
        this.redisTemplate = redisTemplate;
        this.redisScript = redisScript;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        org.springframework.cloud.gateway.route.Route route =
                exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route == null) {
            return chain.filter(exchange);
        }
        String routeId = route.getId();
        RateLimitRule rule = configProvider.ruleFor(routeId);
        if (!rule.enabled()) {
            return chain.filter(exchange);
        }
        String clientIp = resolveClientIp(exchange.getRequest());
        return redisTemplate.execute(redisScript, rateLimitKeys(routeId, clientIp), List.of(
                        String.valueOf(rule.replenishRate()), String.valueOf(rule.burstCapacity()), "",
                        String.valueOf(rule.requestedTokens())))
                .next()
                .map(result -> result.get(0) == 1)
                .onErrorResume(exception -> {
                    log.warn("[RATE_LIMIT] routeId={} | result=fail_open | error={}",
                            routeId, exception.getClass().getSimpleName());
                    return Mono.just(true);
                })
                .flatMap(allowed -> {
                    if (allowed) {
                        log.debug("[RATE_LIMIT] routeId={} | result=allowed", routeId);
                        return chain.filter(exchange);
                    }
                    return reject(exchange, routeId);
                });
    }

    private List<String> rateLimitKeys(String routeId, String clientIp) {
        String keyPrefix = "ai-admin:ai-operations-gateway:rate-limit:{" + routeId + ':' + clientIp + "}";
        return List.of(keyPrefix + ":tokens", keyPrefix + ":timestamp");
    }

    private Mono<Void> reject(ServerWebExchange exchange, String routeId) {
        log.warn("[RATE_LIMIT] routeId={} | result=rejected", routeId);
        return errorResponseWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS, ErrorCodeEnum.GATEWAY_RATE_LIMITED);
    }

    private String resolveClientIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null && remoteAddress.getAddress() != null
                ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
