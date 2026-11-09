package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.CallChainPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 初始化并向下游传递可信的调用链信息。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Component
@Order(-90)
public class CallChainGlobalFilter implements GlobalFilter {
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";
    private static final String LIMIT_PREFIX = "limit=";
    private static final String LENGTH_PREFIX = ",length=";

    private final CallChainPolicy callChainPolicy;
    private final String applicationName;

    public CallChainGlobalFilter(CallChainPolicy callChainPolicy,
                                 @Value("${spring.application.name:ai-operations-gateway}") String applicationName) {
        this.callChainPolicy = callChainPolicy;
        this.applicationName = applicationName;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CALL_CHAIN_HEADER,
                        LIMIT_PREFIX + callChainPolicy.maxCallChainHops()
                                + LENGTH_PREFIX + callChainPolicy.maxCallChainLength() + ";" + applicationName))
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }
}

