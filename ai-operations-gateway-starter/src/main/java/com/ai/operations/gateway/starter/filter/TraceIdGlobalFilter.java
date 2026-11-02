package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.starter.config.MdcContextConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 解析并传递符合 W3C 标准的链路追踪信息。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(TraceIdGlobalFilter.ORDER) // 数值越小越先执行，确保后续过滤器日志携带 traceId。
public class TraceIdGlobalFilter implements GlobalFilter {

    static final String TRACEPARENT_HEADER = "traceparent";
    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_CALL_CHAIN = "callChain";
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";
    public static final int ORDER = -200;
    private static final String TRACEPARENT_VERSION = "00";
    // W3C trace-flags 的最低位为 sampled；01 表示新建链路默认采样。
    private static final String DEFAULT_SAMPLED_TRACE_FLAGS = "01";
    private static final String TRACEPARENT_DELIMITER = "-";
    private static final String TRACE_ID_GROUP = "traceId";
    private static final String SPAN_ID_GROUP = "spanId";
    private static final String TRACE_FLAGS_GROUP = "traceFlags";
    private static final int TRACE_ID_HEX_LENGTH = 32;
    private static final int SPAN_ID_HEX_LENGTH = 16;
    private static final int TRACE_FLAGS_HEX_LENGTH = 2;
    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^" + TRACEPARENT_VERSION + TRACEPARENT_DELIMITER + "(?<" + TRACE_ID_GROUP + ">[0-9a-f]{"
                    + TRACE_ID_HEX_LENGTH + "})" + TRACEPARENT_DELIMITER + "(?<" + SPAN_ID_GROUP
                    + ">[0-9a-f]{" + SPAN_ID_HEX_LENGTH + "})" + TRACEPARENT_DELIMITER + "(?<"
                    + TRACE_FLAGS_GROUP + ">[0-9a-f]{" + TRACE_FLAGS_HEX_LENGTH + "})$");

    @Value("${spring.application.name:ai-operations-gateway}")
    private String applicationName;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. 解析上游链路；非法或缺失时创建新的根链路。
        ServerHttpRequest request = exchange.getRequest();
        HttpHeaders httpHeaders = request.getHeaders();
        String incomingTraceparent = httpHeaders.getFirst(TRACEPARENT_HEADER);
        TraceContext traceContext = parseTraceparent(incomingTraceparent);
        boolean generatedTraceId = Objects.isNull(traceContext);
        if (generatedTraceId) {
            // 生成新的traceContext
            traceContext = new TraceContext(newTraceId(), DEFAULT_SAMPLED_TRACE_FLAGS);
        }
        // 生成最终的traceId
        String traceId = traceContext.traceId();
        String traceparent = String.join(TRACEPARENT_DELIMITER, TRACEPARENT_VERSION, traceId, newSpanId(),
                traceContext.traceFlags());

        // 2. 外部请求统一从网关开始记录调用链，客户端不能指定来源应用。
        String callChain = applicationName;

        final String finalTraceparent = traceparent;
        final String finalCallChain = callChain;

        // 3. 写入标准 traceparent，并由网关在响应提交前覆盖下游同名响应头。
        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.set(TRACEPARENT_HEADER, finalTraceparent);
                    headers.set(CALL_CHAIN_HEADER, finalCallChain);
                })
                .build();
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .response(exchange.getResponse())
                .build();
        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders().set(TRACEPARENT_HEADER, finalTraceparent);
            return Mono.empty();
        });

        // 4. 写入 Reactor Context；请求进入日志仅在 DEBUG 开启时输出。
        return Mono.deferContextual(context -> {
                    MdcContextConfig.withMdc(context, () -> log.debug(
                            "[REQUEST] {} {} | traceIdSource={} | appKey={}",
                            exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
                            generatedTraceId ? "generated" : "traceparent", applicationName));
                    return chain.filter(mutatedExchange);
                })
                .contextWrite(Context.of(
                        MDC_TRACE_ID, traceId,
                        MDC_CALL_CHAIN, finalCallChain
                ));
    }


    private TraceContext parseTraceparent(String traceparent) {
        if (!StringUtils.hasText(traceparent)) {
            return null;
        }
        var matcher = TRACEPARENT_PATTERN.matcher(traceparent.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        String traceIdGroup = matcher.group(TRACE_ID_GROUP);
        String spanId = matcher.group(SPAN_ID_GROUP);
        if (isAllZeros(traceIdGroup) || isAllZeros(spanId)) {
            return null;
        }
        return new TraceContext(traceIdGroup, matcher.group(TRACE_FLAGS_GROUP));
    }



    private String newTraceId() {
        return UUID.randomUUID().toString().replace(TRACEPARENT_DELIMITER, "");
    }

    private String newSpanId() {
        return UUID.randomUUID().toString().replace(TRACEPARENT_DELIMITER, "").substring(0, SPAN_ID_HEX_LENGTH);
    }

    private boolean isAllZeros(String value) {
        return value.chars().allMatch(character -> character == '0');
    }

    private record TraceContext(String traceId, String traceFlags) {
    }

}

