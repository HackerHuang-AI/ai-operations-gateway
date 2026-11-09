package com.ai.operations.gateway.infrastructure.store;

import com.ai.operations.gateway.application.model.SessionIdentity;
import com.ai.operations.gateway.application.model.SessionValidation;
import com.ai.operations.gateway.application.port.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 基于 Redis 的会话存储实现：令牌散列定位会话，Lua 脚本原子完成读取与续期。
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
@Slf4j
@Component
public class RedisSessionStore implements SessionStore {

    private static final String SESSION_KEY_PREFIX = "ai-base:auth:session:{";
    private static final String SESSION_ID_KEY_PREFIX = "ai-base:auth:session-id:{";
    private static final String USER_SESSIONS_KEY_PREFIX = "ai-base:auth:user-sessions:{";
    private static final Duration DEFAULT_SESSION_TTL = Duration.ofHours(4);
    private static final Duration DEFAULT_SESSION_RENEW_WINDOW = Duration.ofMinutes(10);
    private static final RedisScript<String> GET_SESSION_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('GET', KEYS[1])
            if not value then return nil end
            local session = cjson.decode(value)
            local renewWindowMillis = session.renewWindowMillis or tonumber(ARGV[1])
            local sessionTtlMillis = session.ttlMillis or tonumber(ARGV[2])
            local ttl = redis.call('PTTL', KEYS[1])
            local renewed = ttl > 0 and renewWindowMillis > 0 and sessionTtlMillis > renewWindowMillis and ttl <= renewWindowMillis
            return (renewed and '1' or '0') .. value
            """, String.class);
    private static final RedisScript<Long> RENEW_KEY_SCRIPT = new DefaultRedisScript<>("""
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then return 0 end
            if ttl < tonumber(ARGV[1]) then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return 1
            """, Long.class);
    private static final RedisScript<Long> RENEW_SESSION_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local ttl = redis.call('PTTL', KEYS[1])
            if ttl <= 0 then return 0 end
            if ttl < tonumber(ARGV[2]) then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            return 1
            """, Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<SessionValidation> findActiveByToken(String token) {
        final String sessionKey;
        try {
            sessionKey = SESSION_KEY_PREFIX + sha256Hex(token) + "}";
        } catch (IllegalStateException exception) {
            return Mono.just(SessionValidation.rejected("session_lookup_failed"));
        }
        return getSession(sessionKey)
                .map(this::readSessionRenewal)
                .defaultIfEmpty(SessionRenewal.rejected("session_not_found"))
                .onErrorReturn(SessionRenewal.rejected("session_lookup_failed"))
                .flatMap(sessionRenewal -> {
                    SessionLookup sessionLookup = sessionRenewal.sessionLookup();
                    if (sessionLookup.session() == null) {
                        return Mono.just(SessionValidation.rejected(sessionLookup.rejectedReason()));
                    }
                    Mono<Boolean> renewal = sessionRenewal.renewed()
                            ? renewSession(sessionKey, sessionRenewal)
                            : Mono.just(true);
                    return renewal.map(renewed -> renewed
                            ? SessionValidation.authenticated(sessionLookup.session())
                            : SessionValidation.rejected("session_renewal_failed"));
                });
    }

    private Mono<String> getSession(String sessionKey) {
        return redisTemplate.execute(GET_SESSION_SCRIPT, List.of(sessionKey),
                List.of(String.valueOf(DEFAULT_SESSION_RENEW_WINDOW.toMillis()),
                        String.valueOf(DEFAULT_SESSION_TTL.toMillis()))).next();
    }

    private Mono<Boolean> renewSession(String sessionKey, SessionRenewal renewal) {
        SessionIdentity session = renewal.sessionLookup().session();
        return Mono.defer(() -> Mono.zip(
                        renewKey(SESSION_ID_KEY_PREFIX + session.sessionId() + "}", session),
                        renewKey(USER_SESSIONS_KEY_PREFIX + session.userId() + "}", session))
                .flatMap(results -> results.getT1() && results.getT2()
                        ? redisTemplate.execute(RENEW_SESSION_SCRIPT, List.of(sessionKey),
                                List.of(renewal.json(), String.valueOf(session.ttlMillis())))
                                .next().map(result -> result == 1L).defaultIfEmpty(false)
                        : Mono.just(false)))
                .onErrorReturn(false);
    }

    private Mono<Boolean> renewKey(String key, SessionIdentity session) {
        return redisTemplate.execute(RENEW_KEY_SCRIPT, List.of(key),
                List.of(String.valueOf(session.ttlMillis()))).next()
                .map(result -> result == 1L).defaultIfEmpty(false);
    }

    private SessionRenewal readSessionRenewal(String result) {
        if (result.isEmpty() || (result.charAt(0) != '0' && result.charAt(0) != '1')) {
            return SessionRenewal.rejected("invalid_session");
        }
        String json = result.substring(1);
        return new SessionRenewal(readSession(json), result.charAt(0) == '1', json);
    }

    private SessionLookup readSession(String sessionJson) {
        try {
            JsonNode session = objectMapper.readTree(sessionJson);
            if (session == null || !session.isObject()) {
                return SessionLookup.rejected("invalid_session");
            }
            String sessionId = textualValue(session, "sessionId");
            String userId = textualValue(session, "userId");
            String tenantId = textualValue(session, "tenantId");
            if (sessionId == null || userId == null || tenantId == null) {
                return SessionLookup.rejected("invalid_session");
            }
            return SessionLookup.authenticated(new SessionIdentity(sessionId, userId, tenantId,
                    positiveLongValue(session, "ttlMillis", DEFAULT_SESSION_TTL.toMillis()),
                    positiveLongValue(session, "renewWindowMillis", DEFAULT_SESSION_RENEW_WINDOW.toMillis())));
        } catch (Exception exception) {
            return SessionLookup.rejected("invalid_session");
        }
    }

    private String textualValue(JsonNode session, String fieldName) {
        JsonNode value = session.get(fieldName);
        return value != null && value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private long positiveLongValue(JsonNode session, String fieldName, long defaultValue) {
        JsonNode value = session.get(fieldName);
        return value != null && value.canConvertToLong() && value.longValue() > 0 ? value.longValue() : defaultValue;
    }

    private String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SessionLookup(SessionIdentity session, String rejectedReason) {

        private static SessionLookup authenticated(SessionIdentity session) {
            return new SessionLookup(session, null);
        }

        private static SessionLookup rejected(String reason) {
            return new SessionLookup(null, reason);
        }
    }

    private record SessionRenewal(SessionLookup sessionLookup, boolean renewed, String json) {

        private static SessionRenewal rejected(String reason) {
            return new SessionRenewal(SessionLookup.rejected(reason), false, null);
        }
    }
}
