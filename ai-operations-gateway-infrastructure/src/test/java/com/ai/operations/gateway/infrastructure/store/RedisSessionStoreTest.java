package com.ai.operations.gateway.infrastructure.store;

import com.ai.operations.gateway.application.model.SessionValidation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class RedisSessionStoreTest {

    private static final String SESSION =
            "{\"sessionId\":\"session-1\",\"userId\":\"user-1\",\"tenantId\":\"tenant-1\",\"ttlMillis\":3600000,\"renewWindowMillis\":600000}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final RedisSessionStore store = new RedisSessionStore(redisTemplate, objectMapper);

    @Test
    void returnsSessionWhenLookupSucceedsWithoutRenewal() {
        mockSession(Flux.just("0" + SESSION));

        SessionValidation validation = store.findActiveByToken("token").block();

        assertThat(validation.session()).isNotNull();
        assertThat(validation.session().sessionId()).isEqualTo("session-1");
        assertThat(validation.session().tenantId()).isEqualTo("tenant-1");
        verifyNoRenewal();
    }

    @Test
    void rejectsTokenStoredOutsideUserKeySpace() throws Exception {
        String operationsKey = sessionKey().replace("ai-base:", "ai-operations:");
        when(redisTemplate.execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                eq(List.of(sessionKey())), anyList())).thenReturn(Flux.empty());
        when(redisTemplate.execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                eq(List.of(operationsKey)), anyList())).thenReturn(Flux.just("0" + SESSION));

        assertThat(store.findActiveByToken("token").block().session()).isNull();
        verify(redisTemplate, never()).execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                eq(List.of(operationsKey)), anyList());
        verifyNoRenewal();
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-json", "invalid-tenant", "invalid-user"})
    void rejectsInvalidSessionWithoutRenewal(String scenario) {
        String session = switch (scenario) {
            case "invalid-tenant" -> SESSION.replace("\"tenantId\":\"tenant-1\"", "\"tenantId\":123");
            case "invalid-user" -> SESSION.replace("\"userId\":\"user-1\"", "\"userId\":123");
            default -> "{invalid-json";
        };
        mockSession(Flux.just("1" + session));

        SessionValidation validation = store.findActiveByToken("token").block();

        assertThat(validation.session()).isNull();
        assertThat(validation.rejectionReason()).isEqualTo("invalid_session");
        verifyNoRenewal();
    }

    @Test
    void renewsIndexesBeforeMainSessionWithLookupJsonAndSessionTtl() {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));

        SessionValidation validation = store.findActiveByToken("token").block();

        assertThat(validation.session()).isNotNull();
        var order = inOrder(redisTemplate);
        order.verify(redisTemplate).execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                eq(List.of(sessionKey())), eq(List.of("600000", "14400000")));
        order.verify(redisTemplate).execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of("ai-base:auth:session-id:{session-1}")), eq(List.of("3600000")));
        order.verify(redisTemplate).execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of("ai-base:auth:user-sessions:{user-1}")), eq(List.of("3600000")));
        order.verify(redisTemplate).execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), eq(List.of(SESSION, "3600000")));
        verifyNoMoreInteractions(redisTemplate);
    }

    @ParameterizedTest
    @CsvSource({"session-id,zero", "session-id,empty", "session-id,error",
            "user-sessions,zero", "user-sessions,empty", "user-sessions,error"})
    void rejectsFailedIndexRenewalWithoutRenewingMainSession(String index, String scenario) {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));
        String key = "ai-base:auth:" + index + ":{"
                + (index.equals("session-id") ? "session-1" : "user-1") + "}";
        when(redisTemplate.execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of(key)), anyList())).thenReturn(failedRenewal(scenario));

        SessionValidation validation = store.findActiveByToken("token").block();

        assertThat(validation.session()).isNull();
        assertThat(validation.rejectionReason()).isEqualTo("session_renewal_failed");
        verify(redisTemplate, never()).execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), anyList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "empty", "error"})
    void rejectsChangedMissingOrFailedMainSessionRenewal(String scenario) {
        mockSession(Flux.just("1" + SESSION));
        mockRenewal(Flux.just(1L));
        when(redisTemplate.execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), anyList())).thenReturn(failedRenewal(scenario));

        SessionValidation validation = store.findActiveByToken("token").block();

        assertThat(validation.session()).isNull();
        assertThat(validation.rejectionReason()).isEqualTo("session_renewal_failed");
        verify(redisTemplate).execute(argThat((RedisScript<Long> script) ->
                        script != null && script.getResultType() == Long.class),
                eq(List.of(sessionKey())), eq(List.of(SESSION, "3600000")));
    }

    @Test
    void rejectsMissingSessionAndLookupErrors() {
        when(redisTemplate.execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                anyList(), anyList())).thenReturn(Flux.empty());
        assertThat(store.findActiveByToken("token").block().rejectionReason()).isEqualTo("session_not_found");

        when(redisTemplate.execute(argThat((RedisScript<String> script) ->
                        script != null && script.getResultType() == String.class),
                anyList(), anyList())).thenReturn(Flux.error(new IllegalStateException("redis down")));
        assertThat(store.findActiveByToken("token").block().rejectionReason()).isEqualTo("session_lookup_failed");
    }

    private void mockRenewal(Flux<Long> result) {
        when(redisTemplate.execute(argThat((RedisScript<Long> script) ->
                script != null && script.getResultType() == Long.class), anyList(), anyList())).thenReturn(result);
    }

    private Flux<Long> failedRenewal(String scenario) {
        return switch (scenario) {
            case "zero" -> Flux.just(0L);
            case "empty" -> Flux.empty();
            default -> Flux.error(new IllegalStateException("Redis renewal failed"));
        };
    }

    private String sessionKey() {
        try {
            String tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("token".getBytes(StandardCharsets.UTF_8)));
            return "ai-base:auth:session:{" + tokenHash + "}";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void mockSession(Flux<String> result) {
        when(redisTemplate.execute(argThat((RedisScript<String> script) ->
                script != null && script.getResultType() == String.class), anyList(), anyList())).thenReturn(result);
    }

    private void verifyNoRenewal() {
        verify(redisTemplate, never()).execute(argThat((RedisScript<Long> script) ->
                script != null && script.getResultType() == Long.class), anyList(), anyList());
    }
}
