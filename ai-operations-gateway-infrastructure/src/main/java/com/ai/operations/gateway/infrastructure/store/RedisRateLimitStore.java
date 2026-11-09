package com.ai.operations.gateway.infrastructure.store;

import com.ai.operations.gateway.application.model.GatewayGovernanceConfig.RateLimitRule;
import com.ai.operations.gateway.application.port.RateLimitStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RateLimitStore 实现：基于 Spring Cloud Gateway 官方令牌桶脚本执行 Redis 原子限流。
 */
@Component
public class RedisRateLimitStore implements RateLimitStore {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final RedisScript<List<Long>> redisScript;
    private final String applicationName;

    public RedisRateLimitStore(ReactiveStringRedisTemplate redisTemplate,
                               @Qualifier("redisRequestRateLimiterScript") RedisScript<List<Long>> redisScript,
                               @Value("${spring.application.name:ai-operations-gateway}") String applicationName) {
        this.redisTemplate = redisTemplate;
        this.redisScript = redisScript;
        this.applicationName = applicationName;
    }

    @Override
    public Mono<Boolean> tryAcquire(String routeId, String clientIp, RateLimitRule rule) {
        String keyPrefix = "ai-operations:" + applicationName + ":rate-limit:{" + routeId + ':' + clientIp + "}";
        return redisTemplate.execute(redisScript, List.of(keyPrefix + ":tokens", keyPrefix + ":timestamp"), List.of(
                        String.valueOf(rule.replenishRate()), String.valueOf(rule.burstCapacity()), "",
                        String.valueOf(rule.requestedTokens())))
                .next()
                .map(result -> result.get(0) == 1)
                // 脚本无返回属异常情况，转为错误交由调用方 fail-open，避免 Mono.empty 导致请求悬挂
                .switchIfEmpty(Mono.error(new IllegalStateException("rate limit script returned no result")));
    }
}
