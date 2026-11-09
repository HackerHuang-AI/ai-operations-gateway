package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.DownstreamHeadersPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

/**
 * 按下游应用配置裁剪扩展请求头。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class DownstreamHeaderGlobalFilter implements HttpHeadersFilter {

    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String REAL_IP_HEADER = "X-Real-IP";

    private final DownstreamHeadersPolicy headersPolicy;

    public DownstreamHeaderGlobalFilter(DownstreamHeadersPolicy headersPolicy) {
        this.headersPolicy = headersPolicy;
    }

    @Override
    public HttpHeaders filter(HttpHeaders input, ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String applicationName = route == null ? null : route.getUri().getHost();
        boolean removeSessionId = headersPolicy.shouldRemove(applicationName, SESSION_ID_HEADER);
        boolean removeForwardedFor = headersPolicy.shouldRemove(applicationName, FORWARDED_FOR_HEADER);
        log.debug("[DOWNSTREAM_HEADERS] application={} | removeSessionId={} | removeForwardedFor={}",
                applicationName, removeSessionId, removeForwardedFor);
        HttpHeaders headers = new HttpHeaders();
        headers.addAll(input);
        headers.remove(REAL_IP_HEADER);
        headers.remove("Forwarded");
        if (removeSessionId) {
            headers.remove(SESSION_ID_HEADER);
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (removeForwardedFor || remoteAddress == null || remoteAddress.getAddress() == null) {
            headers.remove(FORWARDED_FOR_HEADER);
        } else {
            headers.set(FORWARDED_FOR_HEADER, remoteAddress.getAddress().getHostAddress());
        }
        return headers;
    }
}
