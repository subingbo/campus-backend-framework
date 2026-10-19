package com.campus.ai.plan;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import lombok.Getter;

import java.util.Map;

/**
 * 带稳定规则 ID 的草稿校验异常：仍继承 BusinessException，HTTP code 与用户可见文案不变，
 * 只额外携带 ruleId/details 供 Trace 记录（不把带地点名的原始 message 写入追踪数据）。
 */
@Getter
public class PlanValidationException extends BusinessException {

    private final String ruleId;
    private final Map<String, Object> details;

    public PlanValidationException(String ruleId, String message) {
        this(ResultCode.BAD_REQUEST.getCode(), ruleId, message, Map.of());
    }

    public PlanValidationException(int code, String ruleId, String message) {
        this(code, ruleId, message, Map.of());
    }

    public PlanValidationException(int code, String ruleId, String message, Map<String, Object> details) {
        super(code, message);
        this.ruleId = ruleId;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }
}
