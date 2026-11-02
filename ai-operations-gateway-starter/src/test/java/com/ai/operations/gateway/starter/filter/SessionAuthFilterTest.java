package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.locator.NacosRouteDefinitionLocator;
import com.ai.operations.gateway.application.provider.GatewayRoutesConfigProvider;
import com.ai.operations.gateway.infrastructure.config.NacosConfig;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class SessionAuthFilterTest {
    private static final String PATH = "/ai-operations/api/test";
    private static final String SESSION = "{\"sessionId\":\"session-1\",\"userId\":\"user-1\",\"clientType\":\"ops\",\"ttlMillis\":3600000,\"renewWindowMillis\":600000}";
    private static final List<String> CLIENT_HEADERS = List.of("Authorization", "X-Internal-User-Id",
            "X-Internal-Tenant-Id", "X-Internal-Session-Id", "X-Call-Chain", "X-Source-AppKey",
            "X-User-Id", "X-Tenant-Id", "X-Session-Id", "X-Client-Type", "X-Internal-Client-Type");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final GatewayRoutesConfigProvider routesConfigProvider = new GatewayRoutesConfigProvider(
            mock(NacosConfig.class), objectMapper, mock(NacosRouteDefinitionLocator.class),
            mock(ApplicationEventPublisher.class), mock(CircuitBreakerRegistry.class), List.of(), List.of());
    private final SessionAuthFilter filter = new SessionAuthFilter(redisTemplate, objectMapper, routesConfigProvider,
            new GatewayErrorResponseWriter(objectMapper, messageSource()));

    @Test
    void forwardsOpsSessionWithoutTenantAndUsesOperationsSessionKey() throws Exception {
        mockSession(Flux.just("0" + SESSION));
        MockServerWebExchange exchange = exchange(PATH, false);

        HttpHeaders headers = forward(exchange);

        assertThat(headers.get("X-Internal-User-Id")).containsExactly("user-1");
        assertThat(headers.get("X-Internal-Session-Id")).containsExactly("session-1");
        assertThat(headers.getFirst("X-Internal-Tenant-Id")).isNull();
        String tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("token".getBytes(StandardCharsets.UTF_8)));
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<String>>argThat(
                        script -> script != null && script.getResultType() == String.class),
                eq(List.of("ai-admin:ai-operations:auth:session:{" + tokenHash + "}")),
                eq(List.of("600000", "14400000")));
        verifyNoMoreInteractions(redisTemplate);
    }

    @Test
    void stripsForgedHeadersAndOverridesTrustedUserAndSession() {
        mockSession(Flux.just("0" + SESSION));

        HttpHeaders headers = forward(exchange(PATH, true));

        assertThat(headers.get("X-Internal-User-Id")).containsExactly("user-1");
        assertThat(headers.get("X-Internal-Session-Id")).containsExactly("session-1");
        assertThat(headers.keySet()).containsOnly("X-Internal-User-Id", "X-Internal-Session-Id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"usr", "missing", "invalid-json", "invalid-user"})
    void rejectsInvalidSession(String scenario) throws Exception {
        String session = switch (scenario) {
            case "usr" -> SESSION.replace("\"clientType\":\"ops\"", "\"clientType\":\"usr\"");
            case "missing" -> SESSION.replace("\"clientType\":\"ops\",", "");
            case "invalid-user" -> SESSION.replace("\"userId\":\"user-1\"", "\"userId\":123");
            default -> "{invalid-json";
        };
        mockSession(Flux.just("1" + session));

        assertUnauthorized(exchange(PATH, true));
        verify(redisTemplate, never()).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                script -> script != null && script.getResultType() == Long.class), anyList(), anyList());
    }

    @Test
    void rejectsMissingBearerWithoutRedisLookup() throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(PATH)
                .header("X-Client-Type", "ops"));

        assertUnauthorized(exchange);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void rejectsExpiredRedisSession() throws Exception {
        mockSession(Flux.empty());

        assertUnauthorized(exchange(PATH, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/ai-operations/api/access/login", "/ai-operations/api/access/sms-code/send"})
    void stripsWhitelistHeadersWithoutRedisLookup(String path) {
        HttpHeaders headers = forward(exchange(path, true));

        CLIENT_HEADERS.forEach(header -> assertThat(headers.containsKey(header)).as(header).isFalse());
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void renewsBothOperationsIndexesUsingSessionTtl() throws Exception {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));

        forward(exchange(PATH, false));

        var order = inOrder(redisTemplate);
        order.verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<String>>argThat(
                        script -> script != null && script.getResultType() == String.class),
                eq(List.of(sessionKey())), eq(List.of("600000", "14400000")));
        order.verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of("ai-admin:ai-operations:auth:session-id:{session-1}")), eq(List.of("3600000")));
        order.verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of("ai-admin:ai-operations:auth:user-sessions:{user-1}")), eq(List.of("3600000")));
        order.verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), eq(List.of(SESSION, "3600000")));
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @CsvSource({"session-id,zero", "session-id,empty", "session-id,error",
            "user-sessions,zero", "user-sessions,empty", "user-sessions,error"})
    void rejectsFailedIndexRenewalWithoutRenewingMainSession(String index, String scenario) throws Exception {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));
        String key = "ai-admin:ai-operations:auth:" + index + ":{"
                + (index.equals("session-id") ? "session-1" : "user-1") + "}";
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of(key)), anyList())).thenReturn(failedRenewal(scenario));

        assertUnauthorized(exchange(PATH, false));
        verify(redisTemplate, never()).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "empty", "error"})
    void rejectsChangedMissingOrFailedMainSessionRenewal(String scenario) throws Exception {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), anyList())).thenReturn(failedRenewal(scenario));

        assertUnauthorized(exchange(PATH, false));
        verify(redisTemplate).execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                        script -> script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), eq(List.of(SESSION, "3600000")));
    }

    private void mockRenewal(Flux<Long> result) {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>argThat(
                script -> script != null && script.getResultType() == Long.class), anyList(), anyList())).thenReturn(result);
    }

    private Flux<Long> failedRenewal(String scenario) {
        return switch (scenario) {
            case "zero" -> Flux.just(0L);
            case "empty" -> Flux.empty();
            default -> Flux.error(new IllegalStateException("Redis renewal failed"));
        };
    }

    private String sessionKey() throws Exception {
        String tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("token".getBytes(StandardCharsets.UTF_8)));
        return "ai-admin:ai-operations:auth:session:{" + tokenHash + "}";
    }

    private void mockSession(Flux<String> result) {
        when(redisTemplate.execute(org.mockito.ArgumentMatchers.<RedisScript<String>>argThat(
                script -> script != null && script.getResultType() == String.class), anyList(), anyList())).thenReturn(result);
    }

    private HttpHeaders forward(MockServerWebExchange exchange) {
        AtomicReference<HttpHeaders> headers = new AtomicReference<>();
        filter.filter(exchange, request -> {
            headers.set(request.getRequest().getHeaders());
            return Mono.empty();
        }).block();
        assertThat(headers.get()).isNotNull();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        return headers.get();
    }

    private void assertUnauthorized(MockServerWebExchange exchange) throws Exception {
        AtomicReference<Boolean> forwarded = new AtomicReference<>(false);
        filter.filter(exchange, request -> {
            forwarded.set(true);
            return null;
        }).block();
        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body.path("errorCode").asText()).isEqualTo("1301001");
        assertThat(forwarded.get()).isFalse();
    }

    private MockServerWebExchange exchange(String path, boolean forgedHeaders) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get(path);
        if (forgedHeaders) {
            CLIENT_HEADERS.stream().filter(header -> !HttpHeaders.AUTHORIZATION.equals(header))
                    .forEach(header -> request.header(header, "attacker", "second-attacker"));
        }
        return MockServerWebExchange.from(request.header(HttpHeaders.AUTHORIZATION, "Bearer token"));
    }

    private ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}

