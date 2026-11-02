package com.ai.operations.gateway.starter.common;

import com.ai.operations.gateway.starter.enums.ErrorCodeEnum;
import com.ai.operations.gateway.starter.enums.ResultCodeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 封装网关统一响应数据。
 *
 * @author HUANGcong
 * @since 2026-09-24
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private ResultCodeEnum code;
    private String message;
    private String errorCode;
    private T data;
    private List<ErrorItem> errors;

    public static <T> Result<T> error(ErrorCodeEnum errorCode, String message) {
        return new Result<>(ResultCodeEnum.ERROR, message, errorCode.getCode(), null, List.of());
    }
}

