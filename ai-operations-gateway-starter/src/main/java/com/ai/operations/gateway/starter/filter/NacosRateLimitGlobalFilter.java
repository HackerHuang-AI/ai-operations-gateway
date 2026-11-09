package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.GatewayRateLimitPolicy;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.application.port.RateLimitStore;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

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

    private final GatewayRateLimitPolicy rateLimitPolicy;
    private final RateLimitStore rateLimitStore;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public NacosRateLimitGlobalFilter(GatewayRateLimitPolicy rateLimitPolicy,
                                      RateLimitStore rateLimitStore,
                                      GatewayErrorResponseWriter errorResponseWriter) {
        this.rateLimitPolicy = rateLimitPolicy;
        this.rateLimitStore = rateLimitStore;
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
        RateLimitRule rule = rateLimitPolicy.ruleFor(routeId);
        if (!rule.enabled()) {
            return chain.filter(exchange);
        }
        String clientIp = resolveClientIp(exchange.getRequest());
        return rateLimitStore.tryAcquire(routeId, clientIp, rule)
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
