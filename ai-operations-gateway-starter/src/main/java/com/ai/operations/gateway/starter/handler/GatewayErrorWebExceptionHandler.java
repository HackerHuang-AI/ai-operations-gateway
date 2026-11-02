package com.ai.operations.gateway.starter.handler;

import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 处理网关未捕获的异常并返回统一错误响应。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {
    private final GatewayErrorResponseWriter errorResponseWriter;

    @Override
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable throwable) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(throwable);
        }
        HttpStatusCode status = throwable instanceof ResponseStatusException exception
                ? exception.getStatusCode() : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is4xxClientError()) {
            log.warn("[GATEWAY] request error", throwable);
        } else {
            log.error("[GATEWAY] unexpected error", throwable);
        }
        return errorResponseWriter.write(exchange, status, ErrorCodeEnum.GATEWAY_SYSTEM_ERROR);
    }
}

