package com.ai.operations.gateway.application.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持有并提供调用链治理参数，参数由配置加载方更新，本服务负责合法性校验。
 *
 * @author HUANGcong
 * @since 2026-11-07
 */
@Component
public class CallChainPolicy {

    public static final int DEFAULT_MAX_CALL_CHAIN_HOPS = 64;
    public static final int DEFAULT_MAX_CALL_CHAIN_LENGTH = 2048;
    public static final int MIN_MAX_CALL_CHAIN_LENGTH = 64;

    private final AtomicInteger maxCallChainHops = new AtomicInteger(DEFAULT_MAX_CALL_CHAIN_HOPS);
    private final AtomicInteger maxCallChainLength = new AtomicInteger(DEFAULT_MAX_CALL_CHAIN_LENGTH);

    public int maxCallChainHops() {
        return maxCallChainHops.get();
    }

    public int maxCallChainLength() {
        return maxCallChainLength.get();
    }

    /**
     * 校验并应用新的调用链参数；入参为 null 表示重置为默认值。
     *
     * @throws IllegalArgumentException 参数非法时抛出，调用方应保留旧值
     */
    public void replace(Integer hops, Integer length) {
        if (hops == null && length == null) {
            maxCallChainHops.set(DEFAULT_MAX_CALL_CHAIN_HOPS);
            maxCallChainLength.set(DEFAULT_MAX_CALL_CHAIN_LENGTH);
            return;
        }
        if (hops == null || hops < 2 || length == null || length < MIN_MAX_CALL_CHAIN_LENGTH) {
            throw new IllegalArgumentException("maxCallChainHops must be at least 2 and maxCallChainLength at least 64");
        }
        maxCallChainHops.set(hops);
        maxCallChainLength.set(length);
    }
}
