package com.ai.operations.gateway.starter.common;

import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 写入网关统一错误响应。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Component
@RequiredArgsConstructor
public class GatewayErrorResponseWriter {
    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, ErrorCodeEnum errorCode) {
        return Mono.defer(() -> {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
            try {
                byte[] body = objectMapper.writeValueAsBytes(errorAttributes(exchange, errorCode));
                return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
            } catch (JsonProcessingException exception) {
                return Mono.error(exception);
            }
        });
    }

    public Map<String, Object> errorAttributes(ServerWebExchange exchange, ErrorCodeEnum errorCode) {
        Result<Void> result = Result.error(errorCode, message(errorCode, locale(exchange)));
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("code", result.getCode().getCode());
        attributes.put("message", result.getMessage());
        attributes.put("errorCode", result.getErrorCode());
        attributes.put("data", result.getData());
        attributes.put("errors", result.getErrors());
        return attributes;
    }

    private String message(ErrorCodeEnum errorCode, Locale locale) {
        return messageSource.getMessage(errorCode.getMessageKey(), null, errorCode.getDefaultMessage(), locale);
    }

    private Locale locale(ServerWebExchange exchange) {
        try {
            return exchange.getRequest().getHeaders().getAcceptLanguageAsLocales().stream()
                    .findFirst()
                    .filter(locale -> Locale.ENGLISH.getLanguage().equals(locale.getLanguage()))
                    .map(locale -> Locale.US)
                    .orElse(Locale.SIMPLIFIED_CHINESE);
        } catch (IllegalArgumentException exception) {
            return Locale.SIMPLIFIED_CHINESE;
        }
    }
}

