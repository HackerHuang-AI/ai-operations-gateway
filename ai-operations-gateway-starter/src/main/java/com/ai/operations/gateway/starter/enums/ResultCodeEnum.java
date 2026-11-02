package com.ai.operations.gateway.starter.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum ResultCodeEnum {
    ERROR("01", "失败");

    @JsonValue
    private final String code;
    private final String defaultMessage;

    ResultCodeEnum(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}

