package com.ai.operations.gateway.application.model;

/**
 * 会话校验结果：校验通过时携带会话身份，失败时携带拒绝原因。
 *
 * @param session         校验通过时的会话身份，失败时为 null
 * @param rejectionReason 拒绝原因，校验通过时为 null
 * @author HUANGcong
 * @since 2026-11-07
 */
public record SessionValidation(SessionIdentity session, String rejectionReason) {

    public static SessionValidation authenticated(SessionIdentity session) {
        return new SessionValidation(session, null);
    }

    public static SessionValidation rejected(String reason) {
        return new SessionValidation(null, reason);
    }
}
