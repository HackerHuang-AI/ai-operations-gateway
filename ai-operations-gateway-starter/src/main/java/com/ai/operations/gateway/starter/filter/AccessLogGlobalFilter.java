package com.ai.operations.gateway.starter.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
/**
 * 记录网关请求的访问摘要和处理耗时。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(-150) // 数值越小越先执行，位于 TraceId 与鉴权过滤器之间。
public class AccessLogGlobalFilter implements GlobalFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method   = request.getMethod().name();
        String path     = request.getURI().getRawPath();
        String clientIp = resolveClientIp(request);
        long   startMs  = System.currentTimeMillis();

        return chain.filter(exchange)
                .doFinally(signalType -> {
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value() : 0;
                    long latency = System.currentTimeMillis() - startMs;
                    Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
                    String routeId = route == null ? "unmatched" : route.getId();
                    String targetService = route == null ? "unknown" : route.getUri().getHost();
                    log.info("[ACCESS] {} {} | routeId={} | targetService={} | ip={} | status={} | {}ms",
                            method, path, routeId, targetService, clientIp, status, latency);
                });
    }

    /**
     * 依次从 X-Forwarded-For → X-Real-IP → 直连 IP 读取真实客户端 IP。
     * SCG 前置 Nginx 时，X-Forwarded-For 是最常见的透传方式。
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For: client, proxy1, proxy2 → 取第一个
            return xff.split(",")[0].trim();
        }
        String realIp = request.getHeaders().getFirst("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }
}

