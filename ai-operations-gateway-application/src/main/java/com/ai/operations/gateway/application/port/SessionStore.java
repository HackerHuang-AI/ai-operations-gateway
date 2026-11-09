package com.ai.operations.gateway.application.port;

import com.ai.operations.gateway.application.model.SessionValidation;
import reactor.core.publisher.Mono;

/**
 * 会话存储端口：按访问令牌校验会话有效性，并在进入续期窗口时自动续期。
 *
 * <p>实现方负责令牌散列、存储键组织、原子续期等存储细节；调用方只面向会话语义。</p>
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
public interface SessionStore {

    /**
     * 按访问令牌校验会话：会话不存在、已过期或续期失败时返回 rejected 结果。
     *
     * @param token 访问令牌（非空）
     * @return 会话校验结果
     */
    Mono<SessionValidation> findActiveByToken(String token);
}
