package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.AuthWhitelistService;
import com.ai.operations.gateway.application.model.SessionIdentity;
import com.ai.operations.gateway.application.model.SessionValidation;
import com.ai.operations.gateway.application.port.SessionStore;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SessionAuthFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionStore sessionStore = mock(SessionStore.class);
    private final AuthWhitelistService authWhitelistService = mock(AuthWhitelistService.class);
    private final SessionAuthFilter filter = new SessionAuthFilter(sessionStore, authWhitelistService,
            new GatewayErrorResponseWriter(objectMapper, messageSource()));

    @Test
    void forwardsTrustedSessionTenantWhenClientTenantMatches() {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(false);
        when(sessionStore.findActiveByToken("token")).thenReturn(Mono.just(SessionValidation.authenticated(
                new SessionIdentity("session-1", "user-1", "tenant-1", 3600000, 600000))));
        MockServerWebExchange exchange = exchange("tenant-1");
        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();

        filter.filter(exchange, request -> {
            forwardedHeaders.set(request.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedHeaders.get().getFirst("X-Internal-User-Id")).isEqualTo("user-1");
        assertThat(forwardedHeaders.get().getFirst("X-Internal-Tenant-Id")).isEqualTo("tenant-1");
        assertThat(forwardedHeaders.get().getFirst("X-Internal-Session-Id")).isEqualTo("session-1");
        assertThat(forwardedHeaders.get().getFirst("X-Tenant-Id")).isNull();
        assertThat(forwardedHeaders.get().getFirst("Authorization")).isNull();
    }

    @Test
    void whitelistPassesWithoutSessionLookupAndClearsClientIdentity() {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(true);
        MockServerWebExchange exchange = exchange("tenant-1");
        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();

        filter.filter(exchange, request -> {
            forwardedHeaders.set(request.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        verifyNoInteractions(sessionStore);
        assertThat(forwardedHeaders.get().getFirst("Authorization")).isNull();
        assertThat(forwardedHeaders.get().getFirst("X-Tenant-Id")).isNull();
        assertThat(forwardedHeaders.get().getFirst("X-Internal-Tenant-Id")).isNull();
    }

    @Test
    void rejectsRequestWhenClientTenantIsMissing() throws Exception {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(false);
        when(sessionStore.findActiveByToken("token")).thenReturn(Mono.just(SessionValidation.authenticated(
                new SessionIdentity("session-1", "user-1", "tenant-1", 3600000, 600000))));
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token"));

        AtomicReference<HttpHeaders> forwardedHeaders = new AtomicReference<>();
        filter.filter(exchange, request -> {
            forwardedHeaders.set(request.getRequest().getHeaders());
            return Mono.empty();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwardedHeaders.get().getFirst("X-Internal-Tenant-Id")).isEqualTo("tenant-1");
    }

    @Test
    void rejectsMissingBearerToken() throws Exception {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header("X-Tenant-Id", "tenant-1"));

        filter.filter(exchange, request -> {
            throw new AssertionError("Rejected request must not be forwarded");
        }).block();

        JsonNode body = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(body.path("errorCode").asText()).isEqualTo("1301001");
        verifyNoInteractions(sessionStore);
    }

    @Test
    void rejectsWhenSessionStoreRejects() throws Exception {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(false);
        when(sessionStore.findActiveByToken("token"))
                .thenReturn(Mono.just(SessionValidation.rejected("session_not_found")));

        assertUnauthorized(exchange("tenant-1"));
    }

    @Test
    void rejectsWhenSessionStoreErrors() throws Exception {
        when(authWhitelistService.isAuthWhiteListed("/api/test")).thenReturn(false);
        when(sessionStore.findActiveByToken(any())).thenReturn(Mono.error(new IllegalStateException("redis down")));

        assertUnauthorized(exchange("tenant-1"));
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

    private MockServerWebExchange exchange(String tenantId) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                .header("X-Tenant-Id", tenantId)
                .header("X-Internal-Tenant-Id", "attacker-tenant"));
    }

    private ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
