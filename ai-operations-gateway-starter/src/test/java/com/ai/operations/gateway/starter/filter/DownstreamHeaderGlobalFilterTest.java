package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.DownstreamHeadersPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DownstreamHeaderGlobalFilterTest {
    private final DownstreamHeadersPolicy headersPolicy = mock(DownstreamHeadersPolicy.class);
    private final DownstreamHeaderGlobalFilter filter = new DownstreamHeaderGlobalFilter(headersPolicy);
    private final XForwardedHeadersFilter frameworkFilter = new XForwardedHeadersFilter();

    @ParameterizedTest
    @ValueSource(strings = {"ai-operations", "ai-base"})
    void overwritesFrameworkForwardedChainWithRemoteIpForAllowedApplication(String application) {
        MockServerWebExchange exchange = exchange(application, new InetSocketAddress("192.0.2.10", 12345));
        HttpHeaders generated = frameworkFilter.filter(exchange.getRequest().getHeaders(), exchange);
        assertThat(generated.getFirst("X-Forwarded-For")).contains("198.51.100.1", "192.0.2.10");
        assertThat(DownstreamHeaderGlobalFilter.class.getAnnotation(Order.class).value())
                .isGreaterThan(frameworkFilter.getOrder());

        HttpHeaders result = filter.filter(HttpHeaders.readOnlyHttpHeaders(generated), exchange);

        assertThat(result.get("X-Forwarded-For")).containsExactly("192.0.2.10");
        assertThat(result.getFirst("X-Internal-Session-Id")).isEqualTo("session-1");
        assertThat(result).doesNotContainKeys("X-Real-IP", "Forwarded");
        assertThat(result.get("X-Forwarded-Host")).isEqualTo(generated.get("X-Forwarded-Host"));
        assertThat(result.getFirst("X-Request-Id")).isEqualTo("request-1");
        assertThat(generated.getFirst("X-Forwarded-For")).contains("198.51.100.1");
        assertThat(generated.getFirst("X-Real-IP")).isEqualTo("198.51.100.2");
        verify(headersPolicy).shouldRemove(application, "X-Internal-Session-Id");
        verify(headersPolicy).shouldRemove(application, "X-Forwarded-For");
    }

    @Test
    void removesSessionAndFrameworkGeneratedForwardedForForDisallowedApplication() {
        when(headersPolicy.shouldRemove("ai-agent", "X-Internal-Session-Id")).thenReturn(true);
        when(headersPolicy.shouldRemove("ai-agent", "X-Forwarded-For")).thenReturn(true);
        MockServerWebExchange exchange = exchange("ai-agent", new InetSocketAddress("192.0.2.10", 12345));
        HttpHeaders generated = frameworkFilter.filter(exchange.getRequest().getHeaders(), exchange);
        assertThat(generated.getFirst("X-Forwarded-For")).contains("192.0.2.10");

        HttpHeaders result = filter.filter(HttpHeaders.readOnlyHttpHeaders(generated), exchange);

        assertThat(result).doesNotContainKeys("X-Internal-Session-Id", "X-Forwarded-For", "X-Real-IP", "Forwarded");
        assertThat(generated.getFirst("X-Internal-Session-Id")).isEqualTo("session-1");
        assertThat(result.getFirst("X-Request-Id")).isEqualTo("request-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "unresolved"})
    void removesExternalIpWhenRemoteAddressIsUnavailable(String scenario) {
        InetSocketAddress remote = "unresolved".equals(scenario)
                ? InetSocketAddress.createUnresolved("unresolved.invalid", 12345) : null;
        MockServerWebExchange exchange = exchange("ai-base", remote);

        HttpHeaders result = filter.filter(exchange.getRequest().getHeaders(), exchange);

        assertThat(result).doesNotContainKeys("X-Forwarded-For", "X-Real-IP", "Forwarded");
        assertThat(result.getFirst("X-Internal-Session-Id")).isEqualTo("session-1");
    }

    private MockServerWebExchange exchange(String application, InetSocketAddress remote) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("http://gateway.example/api/test")
                .header("X-Forwarded-For", "198.51.100.1")
                .header("X-Real-IP", "198.51.100.2")
                .header("Forwarded", "for=198.51.100.3")
                .header("X-Internal-Session-Id", "session-1")
                .header("X-Request-Id", "request-1");
        if (remote != null) {
            request.remoteAddress(remote);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                Route.async().id("test-route").uri("lb://" + application).predicate(value -> true).build());
        return exchange;
    }
}

