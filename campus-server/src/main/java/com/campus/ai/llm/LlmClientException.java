package com.campus.ai.llm;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import lombok.Getter;

/**
 * 模型调用的稳定错误分类；原始响应体/密钥不进入异常消息与追踪数据。
 */
@Getter
public class LlmClientException extends BusinessException {

    private final String errorType;
    private final Integer httpStatus;

    public LlmClientException(String errorType, Integer httpStatus, String message) {
        this(ResultCode.INTERNAL_ERROR.getCode(), errorType, httpStatus, message);
    }

    public LlmClientException(int code, String errorType, Integer httpStatus, String message) {
        super(code, message);
        this.errorType = errorType;
        this.httpStatus = httpStatus;
    }
}
