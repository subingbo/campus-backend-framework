package com.campus.common.exception;

import com.campus.common.result.ResultCode;

public class RateLimitException extends BusinessException {

    public RateLimitException() {
        super(ResultCode.TOO_MANY_REQUESTS);
    }

    public RateLimitException(String message) {
        super(ResultCode.TOO_MANY_REQUESTS.getCode(), message);
    }
}
