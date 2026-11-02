package com.ai.operations.gateway.starter.common;

/**
 * 描述网关响应中的单个错误项。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
public record ErrorItem(String errorCode, String message) {
}

