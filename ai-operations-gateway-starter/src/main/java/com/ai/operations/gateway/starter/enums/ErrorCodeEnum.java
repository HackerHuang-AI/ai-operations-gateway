package com.ai.operations.gateway.starter.enums;

import lombok.Getter;

@Getter
public enum ErrorCodeEnum {
    GATEWAY_UNAUTHENTICATED("1301001", "error.1301001", "未认证或登录已过期"),
    GATEWAY_RATE_LIMITED("1301002", "error.1301002", "请求过于频繁，请稍后重试"),
    GATEWAY_SYSTEM_ERROR("1301999", "error.1301999", "网关服务异常，请稍后重试");

    private final String code;
    private final String messageKey;
    private final String defaultMessage;

    ErrorCodeEnum(String code, String messageKey, String defaultMessage) {
        this.code = code;
        this.messageKey = messageKey;
        this.defaultMessage = defaultMessage;
    }
}

