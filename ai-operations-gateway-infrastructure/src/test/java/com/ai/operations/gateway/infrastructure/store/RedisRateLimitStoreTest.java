package com.ai.operations.gateway.infrastructure.store;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisRateLimitStoreTest {

    private final ReactiveStringRedisTemplate redisTemplate = mock(ReactiveStringRedisTemplate.class);
    private final RedisScript<List<Long>> script = RedisScript.of("return {1, 10}");
    private final RedisRateLimitStore store = new RedisRateLimitStore(redisTemplate, script, "ai-operations-gateway");

    @Test
    void allowsRequestAndBuildsNamespacedBucketKeysFromRule() {
        when(redisTemplate.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(1L, 9L)));

        Boolean allowed = store.tryAcquire("test-route", "192.0.2.10", new RateLimitRule(true, 10, 20, 1)).block();

        assertThat(allowed).isTrue();
        verify(redisTemplate).execute(script,
                List.of("ai-operations:ai-operations-gateway:rate-limit:{test-route:192.0.2.10}:tokens",
                        "ai-operations:ai-operations-gateway:rate-limit:{test-route:192.0.2.10}:timestamp"),
                List.of("10", "20", "", "1"));
    }

    @Test
    void deniesRequestWhenScriptReportsNoToken() {
        when(redisTemplate.execute(eq(script), anyList(), anyList())).thenReturn(Flux.just(List.of(0L, 0L)));

        assertThat(store.tryAcquire("test-route", "192.0.2.10", new RateLimitRule(true, 10, 20, 1)).block())
                .isFalse();
    }

    @Test
    void convertsEmptyScriptResultToErrorInsteadOfSilentEmpty() {
        when(redisTemplate.execute(eq(script), anyList(), anyList())).thenReturn(Flux.empty());

        assertThatThrownBy(() -> store.tryAcquire("test-route", "192.0.2.10",
                new RateLimitRule(true, 10, 20, 1)).block())
                .hasMessageContaining("rate limit script returned no result");
    }

    @Test
    void propagatesRedisFailureToCallerForFailOpenDecision() {
        when(redisTemplate.execute(eq(script), anyList(), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("Redis unavailable")));

        assertThatThrownBy(() -> store.tryAcquire("test-route", "192.0.2.10",
                new RateLimitRule(true, 10, 20, 1)).block())
                .hasMessageContaining("Redis unavailable");
    }
}
