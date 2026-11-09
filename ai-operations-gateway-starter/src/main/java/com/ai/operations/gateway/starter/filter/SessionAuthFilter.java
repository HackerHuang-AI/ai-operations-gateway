package com.ai.operations.gateway.starter.filter;

import com.ai.operations.gateway.application.governance.AuthWhitelistService;
import com.ai.operations.gateway.application.model.SessionIdentity;
import com.ai.operations.gateway.application.model.SessionValidation;
import com.ai.operations.gateway.application.port.SessionStore;
import com.ai.operations.gateway.starter.common.GatewayErrorResponseWriter;
import com.ai.operations.gateway.starter.config.MdcContextConfig;
import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.util.List;

/**
 * 校验会话并向下游注入可信身份信息。
 *
 * <p>会话的存储校验与续期由 {@link SessionStore} 端口实现承担，本过滤器只做协议适配：
 * 提取令牌、判断白名单与租户上下文、注入身份头、写拒绝响应。</p>
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
    private static final String USER_ID_HEADER = "X-Internal-User-Id";
    private static final String TENANT_ID_HEADER = "X-Internal-Tenant-Id";
    private static final String SESSION_ID_HEADER = "X-Internal-Session-Id";
    private static final String CALL_CHAIN_HEADER = "X-Call-Chain";
    private static final String SOURCE_APP_KEY_HEADER = "X-Source-AppKey";
    private static final String LEGACY_USER_ID_HEADER = "X-User-Id";
    private static final String LEGACY_TENANT_ID_HEADER = "X-Tenant-Id";
    private static final String LEGACY_SESSION_ID_HEADER = "X-Session-Id";

    private final SessionStore sessionStore;
    private final AuthWhitelistService authWhitelistService;
    private final GatewayErrorResponseWriter errorResponseWriter;

    public SessionAuthFilter(SessionStore sessionStore, AuthWhitelistService authWhitelistService,
                             GatewayErrorResponseWriter errorResponseWriter) {
        this.sessionStore = sessionStore;
        this.authWhitelistService = authWhitelistService;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return Mono.deferContextual(context -> {
            String path = exchange.getRequest().getPath().value();
            if (authWhitelistService.isAuthWhiteListed(path)) {
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

            return sessionStore.findActiveByToken(token)
                    .defaultIfEmpty(SessionValidation.rejected("session_not_found"))
                    .onErrorReturn(SessionValidation.rejected("session_lookup_failed"))
                    .flatMap(validation -> {
                        if (validation.session() == null) {
                            log.warn("[AUTH] path={} | result=rejected | reason={}",
                                    path, validation.rejectionReason());
                            return reject(sanitizedExchange);
                        }
                        return chain.filter(withSessionIdentityHeaders(sanitizedExchange, validation.session()))
                                .doOnSuccess(ignored -> logAuthenticatedSuccess(path));
                    });
        });
    }

    private String extractBearerToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        return StringUtils.hasText(token) ? token : null;
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
                    headers.set(TENANT_ID_HEADER, session.tenantId());
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
}
