package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.provider.GatewayRoutesConfigProvider;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.ai.operations.gateway.starter.config.MdcContextConfig;
import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 校验会话并向下游注入可信身份信息。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Slf4j
@Component
@Order(-100) // 数值越小越先执行，在 TraceId 和访问日志之后执行鉴权。
public class SessionAuthFilter implements GlobalFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SESSION_KEY_PREFIX = "ai-operations:auth:session:{";
    private static final String SESSION_ID_KEY_PREFIX = "ai-operations:auth:session-id:{";
    private static final String USER_SESSIONS_KEY_PREFIX = "ai-operations:auth:user-sessions:{";
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
    private static final String USER_ID_HEADER = "X-Internal-User-Id";
    private static final String TENANT_ID_HEADER = "X-Internal-Tenant-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";
    private static final String SOURCE_APP_KEY_HEADER = "X-Source-AppKey";
    private static final String LEGACY_USER_ID_HEADER = "X-User-Id";
    private static final String LEGACY_TENANT_ID_HEADER = "X-Tenant-Id";
    private static final String LEGACY_SESSION_ID_HEADER = "X-Session-Id";
    private static final String CLIENT_TYPE_HEADER = "X-Client-Type";
    private static final String INTERNAL_CLIENT_TYPE_HEADER = "X-Internal-Client-Type";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final GatewayRoutesConfigProvider routesConfigProvider;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public SessionAuthFilter(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             GatewayRoutesConfigProvider routesConfigProvider,
                             GatewayErrorResponseWriter errorResponseWriter) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.routesConfigProvider = routesConfigProvider;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.deferContextual(context -> {
            String path = exchange.getRequest().getPath().value();
            if (routesConfigProvider.isAuthWhiteListed(path)) {
                logWhitelistSuccess(context, path);
                return chain.filter(withoutClientIdentityHeaders(exchange));
            }

            String token = extractBearerToken(exchange);
            ServerWebExchange sanitizedExchange = withoutClientIdentityHeaders(exchange);
            if (token == null) {
                MdcContextConfig.withMdc(context, () -> log.warn(
                        "[AUTH] path={} | result=rejected | reason={}", path, "missing_or_invalid_bearer"));
                return reject(sanitizedExchange);
            }

            final String sessionKey;
            try {
                sessionKey = SESSION_KEY_PREFIX + sha256Hex(token) + "}";
            } catch (IllegalStateException exception) {
                MdcContextConfig.withMdc(context, () -> log.warn(
                        "[AUTH] path={} | result=rejected | reason={}", path, "session_lookup_failed"));
                return reject(sanitizedExchange);
            }

            return getSession(sessionKey)
                    .map(this::readSessionRenewal)
                    .defaultIfEmpty(SessionRenewal.rejected("session_not_found"))
                    .onErrorReturn(SessionRenewal.rejected("session_lookup_failed"))
                    .flatMap(sessionRenewal -> {
                        SessionLookup sessionLookup = sessionRenewal.sessionLookup();
                        if (sessionLookup.session() == null) {
                            log.warn("[AUTH] path={} | result=rejected | reason={}",
                                    path, sessionLookup.rejectedReason());
                            return reject(sanitizedExchange);
                        }
                        Mono<Boolean> renewal = sessionRenewal.renewed()
                                ? renewSession(sessionKey, sessionRenewal)
                                : Mono.just(true);
                        return renewal.flatMap(renewed -> {
                            if (!renewed) {
                                log.warn("[AUTH] path={} | result=rejected | reason={}", path, "session_renewal_failed");
                                return reject(sanitizedExchange);
                            }
                            return chain.filter(withSessionIdentityHeaders(sanitizedExchange, sessionLookup.session()))
                                    .doOnSuccess(ignored -> logAuthenticatedSuccess(path));
                        });
                    });
        });
    }

    private Mono<String> getSession(String sessionKey) {
        return redisTemplate.execute(GET_SESSION_SCRIPT, List.of(sessionKey),
                List.of(String.valueOf(DEFAULT_SESSION_RENEW_WINDOW.toMillis()), String.valueOf(DEFAULT_SESSION_TTL.toMillis()))).next();
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

    private String extractBearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        return StringUtils.hasText(token) ? token : null;
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
            if (sessionId == null || userId == null) {
                return SessionLookup.rejected("invalid_session");
            }
            return SessionLookup.authenticated(new SessionIdentity(sessionId, userId,
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

    private ServerWebExchange withoutClientIdentityHeaders(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(AUTHORIZATION_HEADER);
                    headers.remove(USER_ID_HEADER);
                    headers.remove(TENANT_ID_HEADER);
                    headers.remove(SESSION_ID_HEADER);
                    headers.remove(CALL_CHAIN_HEADER);
                    headers.remove(SOURCE_APP_KEY_HEADER);
                    headers.remove(CLIENT_TYPE_HEADER);
                    headers.remove(INTERNAL_CLIENT_TYPE_HEADER);
                    headers.remove(LEGACY_USER_ID_HEADER);
                    headers.remove(LEGACY_TENANT_ID_HEADER);
                    headers.remove(LEGACY_SESSION_ID_HEADER);
                }))
                .build();
    }

    private ServerWebExchange withSessionIdentityHeaders(ServerWebExchange exchange, SessionIdentity session) {
        return exchange.mutate()
                .request(request -> request.headers(headers -> {
                    headers.remove(AUTHORIZATION_HEADER);
                    headers.remove(USER_ID_HEADER);
                    headers.remove(TENANT_ID_HEADER);
                    headers.remove(SESSION_ID_HEADER);
                    headers.remove(LEGACY_USER_ID_HEADER);
                    headers.remove(LEGACY_TENANT_ID_HEADER);
                    headers.remove(LEGACY_SESSION_ID_HEADER);
                    headers.set(USER_ID_HEADER, session.userId());
                    headers.set(SESSION_ID_HEADER, session.sessionId());
                }))
                .build();
    }

    private Mono<Void> reject(ServerWebExchange exchange) {
        return errorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, ErrorCodeEnum.GATEWAY_UNAUTHENTICATED);
    }

    private void logWhitelistSuccess(ContextView context, String path) {
        MdcContextConfig.withMdc(context, () ->
                log.debug("[AUTH] path={} | result=success | reason=whitelist", path));
    }

    private void logAuthenticatedSuccess(String path) {
        log.debug("[AUTH] path={} | result=success", path);
    }

    private String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record SessionIdentity(String sessionId, String userId, long ttlMillis,
                                   long renewWindowMillis) {
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
