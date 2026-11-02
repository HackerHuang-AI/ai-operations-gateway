package com.ai.operations.gateway.starter.common;

import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import com.ai.operations.gateway.starter.handler.GatewayErrorWebExceptionHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorResponseWriterTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final GatewayErrorResponseWriter writer = new GatewayErrorResponseWriter(objectMapper, messageSource());

    @ParameterizedTest
    @CsvSource({
            "zh,未认证或登录已过期",
            "en-US,Authentication is required or the login session has expired",
            "en,Authentication is required or the login session has expired"
    })
    void writesLocalizedUnifiedErrorResponse(String acceptLanguage, String expectedMessage) throws Exception {
        MockServerWebExchange exchange = exchange(acceptLanguage);

        writer.write(exchange, HttpStatus.UNAUTHORIZED, ErrorCodeEnum.GATEWAY_UNAUTHENTICATED).block();

        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange.getResponse().getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(body.fieldNames()).toIterable().containsExactly("code", "message", "errorCode", "data", "errors");
        assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
    }

    @Test
    void handlesUnexpectedErrorsWithUnifiedResponse() throws Exception {
        MockServerWebExchange exchange = exchange("en-US");

        new GatewayErrorWebExceptionHandler(writer).handle(exchange, new RuntimeException()).block();

        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(body.fieldNames()).toIterable().containsExactly("code", "message", "errorCode", "data", "errors");
        assertThat(body.path("message").asText()).isEqualTo("Gateway service error. Please try again later");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void preservesFrameworkStatusWithUnifiedResponse(int status) throws Exception {
        MockServerWebExchange exchange = exchange("en-US");

        new GatewayErrorWebExceptionHandler(writer).handle(exchange,
                new ResponseStatusException(HttpStatus.valueOf(status), "private downstream details")).block();

        String response = exchange.getResponse().getBodyAsString().block();
        JsonNode body = objectMapper.readTree(response);
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(status);
        assertThat(exchange.getResponse().getHeaders().getContentType().getCharset()).isEqualTo(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(objectMapper.valueToTree(writer.errorAttributes(exchange, ErrorCodeEnum.GATEWAY_SYSTEM_ERROR)));
        assertThat(response).doesNotContain("private downstream details");
    }

    @ParameterizedTest
    @CsvSource({"en_US", "invalid-language"})
    void fallsBackToChineseWhenAcceptLanguageIsInvalid(String acceptLanguage) throws Exception {
        MockServerWebExchange exchange = exchange(acceptLanguage);

        writer.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodeEnum.GATEWAY_SYSTEM_ERROR).block();

        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(body.path("message").asText()).isEqualTo("网关服务异常，请稍后重试");
    }

    private MockServerWebExchange exchange(String acceptLanguage) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/")
                .header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage));
    }

    private ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}

