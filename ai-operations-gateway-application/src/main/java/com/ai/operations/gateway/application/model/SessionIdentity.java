package com.ai.operations.gateway.application.model;

/**
 * 通过鉴权的会话身份信息。
 *
 * @param sessionId          会话唯一标识
 * @param userId             用户标识
 * @param tenantId           会话归属租户标识
 * @param ttlMillis          会话剩余有效期（毫秒）
 * @param renewWindowMillis  续期窗口（毫秒），剩余有效期进入窗口后会话自动续期
 * @author HUANGcong
 * @since 2026-11-07
 */
public record SessionIdentity(String sessionId, String userId, String tenantId, long ttlMillis,
                              long renewWindowMillis) {
}
