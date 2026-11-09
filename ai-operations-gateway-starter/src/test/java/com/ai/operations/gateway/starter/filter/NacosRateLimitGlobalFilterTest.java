package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.GatewayRateLimitPolicy;
import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.application.port.RateLimitStore;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NacosRateLimitGlobalFilterTest {
    private final GatewayRateLimitPolicy rateLimitPolicy = mock(GatewayRateLimitPolicy.class);
    private final RateLimitStore rateLimitStore = mock(RateLimitStore.class);
    private final GatewayErrorResponseWriter writer = mock(GatewayErrorResponseWriter.class);
    private final GatewayFilterChain chain = mock(GatewayFilterChain.class);
    private final NacosRateLimitGlobalFilter filter =
            new NacosRateLimitGlobalFilter(rateLimitPolicy, rateLimitStore, writer);

    @Test
    void usesSameRemoteIpBucketDespiteDifferentForgedForwardingHeaders() {
        RateLimitRule rule = new RateLimitRule(true, 10, 20, 1);
        when(rateLimitPolicy.ruleFor("test-route")).thenReturn(rule);
        when(chain.filter(any())).thenReturn(Mono.empty());
        List<String> capturedIps = new ArrayList<>();
        when(rateLimitStore.tryAcquire(eq("test-route"), any(), eq(rule))).thenAnswer(invocation -> {
            capturedIps.add(invocation.getArgument(1));
            return Mono.just(true);
        });
        MockServerWebExchange first = exchange("198.51.100.1");
        MockServerWebExchange second = exchange("198.51.100.2");

        filter.filter(first, chain).block();
        filter.filter(second, chain).block();

        assertThat(capturedIps).containsExactly("192.0.2.10", "192.0.2.10");
        verify(chain).filter(first);
        verify(chain).filter(second);
        verifyNoInteractions(writer);
    }

    @Test
    void keepsFailOpenWhenRateLimitStoreFails() {
        RateLimitRule rule = new RateLimitRule(true, 10, 20, 1);
        when(rateLimitPolicy.ruleFor("test-route")).thenReturn(rule);
        when(rateLimitStore.tryAcquire(eq("test-route"), any(), eq(rule)))
                .thenReturn(Mono.error(new IllegalStateException("Redis unavailable")));
        MockServerWebExchange exchange = exchange("198.51.100.1");
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
        verifyNoInteractions(writer);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void stillRejectsWhenRateLimitStoreDeniesRequest() {
        RateLimitRule rule = new RateLimitRule(true, 10, 20, 1);
        when(rateLimitPolicy.ruleFor("test-route")).thenReturn(rule);
        when(rateLimitStore.tryAcquire(eq("test-route"), any(), eq(rule))).thenReturn(Mono.just(false));
        MockServerWebExchange exchange = exchange("198.51.100.1");
        when(writer.write(exchange, HttpStatus.TOO_MANY_REQUESTS, ErrorCodeEnum.GATEWAY_RATE_LIMITED)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        verify(writer).write(exchange, HttpStatus.TOO_MANY_REQUESTS, ErrorCodeEnum.GATEWAY_RATE_LIMITED);
        verifyNoInteractions(chain);
    }

    private MockServerWebExchange exchange(String forgedIp) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .remoteAddress(new InetSocketAddress("192.0.2.10", 12345))
                .header("X-Forwarded-For", forgedIp)
                .header("X-Real-IP", forgedIp));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("test-route").uri("lb://ai-base").predicate(value -> true).build());
        return exchange;
    }
}
