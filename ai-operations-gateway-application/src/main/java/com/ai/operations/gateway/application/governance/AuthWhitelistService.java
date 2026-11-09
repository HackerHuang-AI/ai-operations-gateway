package com.ai.operations.gateway.application.governance;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有并提供鉴权白名单查询，白名单内容由配置加载方在校验后更新。
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
@Component
public class AuthWhitelistService {

    private static final List<String> DEFAULT_AUTH_WHITELIST = List.of(
            "/ai-base/api/access/login", "/ai-base/api/access/register", "/ai-base/api/access/sms-code/send");

    private final AtomicReference<List<String>> whitelist = new AtomicReference<>(DEFAULT_AUTH_WHITELIST);

    public boolean isAuthWhiteListed(String path) {
        return whitelist.get().stream().anyMatch(pattern -> matches(path, pattern));
    }

    /**
     * 校验并替换白名单快照；非法条目抛出 IllegalArgumentException，由调用方保留旧快照。
     */
    public void replace(List<String> newWhitelist) {
        if (newWhitelist == null) {
            throw new IllegalArgumentException("authWhitelist must not be null");
        }
        List<String> result = new java.util.ArrayList<>();
        for (String pattern : newWhitelist) {
            if (!org.springframework.util.StringUtils.hasText(pattern) || !pattern.startsWith("/")
                    || (pattern.contains("*") && !pattern.endsWith("/**"))
                    || pattern.substring(0, Math.max(0, pattern.length() - 3)).contains("*")) {
                throw new IllegalArgumentException("unsupported auth whitelist pattern: " + pattern);
            }
            result.add(pattern);
        }
        whitelist.set(List.copyOf(result));
    }

    private boolean matches(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return path.equals(pattern);
    }
}
