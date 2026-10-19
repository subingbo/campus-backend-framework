package com.campus.ai.trace.adapter;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.plan.PlanValidationException;
import com.campus.ai.trace.domain.AgentEventType;
import com.campus.ai.trace.domain.TraceStatus;
import com.campus.ai.trace.dto.TraceContext;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.dto.TraceRequest;
import com.campus.ai.trace.service.AgentTraceService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 保存/修订请求的追踪句柄。与 TraceRun 分开：
 * 保存不产生 turn/step/stream，只记录 plan/validate/confirm/revise/activity 与终态。
 */
public final class TraceSaveRun {

    private static final TraceSaveRun NOOP = new TraceSaveRun(null, null);

    private final AgentTraceService service;
    private final TraceRequest request;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public TraceSaveRun(AgentTraceService service, TraceRequest request) {
        this.service = service;
        this.request = request;
    }

    public static TraceSaveRun noop() {
        return NOOP;
    }

    public boolean isNoop() {
        return service == null || request == null;
    }

    public String sessionId() {
        return isNoop() ? null : request.context().sessionId();
    }

    public void bindContext(PlanDto plan) {
        if (isNoop() || plan == null) {
            return;
        }
        Long mapId = plan.getSourceMapIds() == null || plan.getSourceMapIds().isEmpty()
                ? null : plan.getSourceMapIds().get(0);
        service.bindContext(request.context().sessionId(), mapId, plan.getGroupId());
    }

    public void confirmRunning(String operation, int draftVersion, Integer expectedVersion) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", request.context().conversationId());
        data.put("draftVersion", draftVersion);
        data.put("operation", operation);
        if (expectedVersion != null) {
            data.put("expectedVersion", expectedVersion);
        }
        record(AgentEventType.PLAN_CONFIRM, TraceStatus.RUNNING, null, null, null, data, true,
                request.context().conversationId());
    }

    public void validationPassed(String phase, PlanDto plan, int draftVersion) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = planSummary(plan);
        data.put("phase", phase);
        data.put("draftVersion", draftVersion);
        data.put("passed", true);
        data.put("ruleSet", "plan-v1");
        record(AgentEventType.PLAN_VALIDATE, TraceStatus.SUCCESS, null, null, null, data, false, null);
    }

    public void validationFailed(String phase, PlanValidationException ex, int draftVersion) {
        if (isNoop() || ex == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("draftVersion", draftVersion);
        data.put("passed", false);
        data.put("ruleIds", List.of(ex.getRuleId()));
        data.put("details", ex.getDetails());
        record(AgentEventType.PLAN_VALIDATE, TraceStatus.FAILED, null, ex.getRuleId(), null, data,
                false, null);
    }

    public void validationFailed(String phase, String ruleId, int draftVersion) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("phase", phase);
        data.put("draftVersion", draftVersion);
        data.put("passed", false);
        data.put("ruleIds", List.of(ruleId));
        record(AgentEventType.PLAN_VALIDATE, TraceStatus.FAILED, null, ruleId, null, data, false, null);
    }

    public void confirmSuccess(long activityId, int draftVersion, String operation, boolean idempotentReuse) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", request.context().conversationId());
        data.put("draftVersion", draftVersion);
        data.put("activityId", activityId);
        data.put("operation", operation);
        data.put("idempotentReuse", idempotentReuse);
        record(AgentEventType.PLAN_CONFIRM, TraceStatus.SUCCESS, null, null, null, data, true,
                request.context().conversationId());
    }

    public void activityCreated(long activityId, int draftVersion, String operation, boolean idempotentReuse) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("activityId", activityId);
        data.put("draftId", request.context().conversationId());
        data.put("draftVersion", draftVersion);
        data.put("operation", operation);
        data.put("idempotentReuse", idempotentReuse);
        record(AgentEventType.ACTIVITY_CREATED, TraceStatus.SUCCESS, null, null, null, data, true,
                request.context().conversationId());
    }

    public void confirmFailed(String ruleId, int draftVersion, boolean rolledBack) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", request.context().conversationId());
        data.put("draftVersion", draftVersion);
        data.put("rolledBack", rolledBack);
        if (ruleId != null) {
            data.put("ruleIds", List.of(ruleId));
        }
        record(AgentEventType.PLAN_CONFIRM, TraceStatus.FAILED, null, ruleId, null, data, true, null);
    }

    public void saveError(String operation, int errorCode, String ruleId) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", operation);
        data.put("httpStatus", errorCode);
        data.put("ruleIds", ruleId == null ? List.of() : List.of(ruleId));
        data.put("rolledBack", true);
        record(AgentEventType.PLAN_SAVE_ERROR, TraceStatus.FAILED, null,
                ruleId == null ? String.valueOf(errorCode) : ruleId, null, data, true, null);
    }

    public void revise(int fromVersion, int toVersion, boolean changed) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("draftId", request.context().conversationId());
        data.put("fromVersion", fromVersion);
        data.put("toVersion", toVersion);
        data.put("changed", changed);
        record(AgentEventType.PLAN_REVISE, TraceStatus.SUCCESS, null, null, null, data, false, null);
    }

    /** 成功保存后结束 session；session/end 由 writer 在事务内落库并更新 session 状态 */
    public void finishCompleted(String reason) {
        if (isNoop()) {
            return;
        }
        service.finishSession(request.context().sessionId(), TraceStatus.COMPLETED, reason);
    }

    /** 每个请求结束都必须调用（afterCommit/afterCompletion 或异常路径） */
    public void close() {
        if (isNoop() || !closed.compareAndSet(false, true)) {
            return;
        }
        try {
            service.onRunClosed(request.context().sessionId());
        } catch (Exception ignored) {
            // Trace 生命周期必须吞异常
        }
    }

    private Map<String, Object> planSummary(PlanDto plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (plan == null) {
            return data;
        }
        if (plan.getStops() != null) {
            data.put("stopCount", plan.getStops().size());
        }
        if (plan.getTotalTravelMinutes() != null) {
            data.put("totalTravelMinutes", plan.getTotalTravelMinutes());
        }
        if (plan.getRouteConfidence() != null) {
            data.put("routeConfidence", plan.getRouteConfidence());
        }
        if (plan.getConstraintStatus() != null) {
            data.put("constraintStatus", plan.getConstraintStatus());
        }
        if (plan.getGroupId() != null) {
            data.put("groupId", plan.getGroupId());
        }
        return data;
    }

    private void record(String type, String status, String callId, String errorCode,
                        String errorType, Map<String, Object> data, boolean critical, Long draftId) {
        if (isNoop()) {
            return;
        }
        try {
            TraceContext context = request.context();
            service.record(TraceEventRecord.builder()
                    .sessionId(context.sessionId())
                    .traceId(context.traceId())
                    .occurredAtMs(System.currentTimeMillis())
                    .turn(context.turn())
                    .step(null)
                    .type(type)
                    .callId(callId)
                    .status(status)
                    .errorCode(errorCode)
                    .draftId(draftId)
                    .data(data == null ? Map.of() : data)
                    .critical(critical)
                    .build());
        } catch (Exception ignored) {
            // 追踪绝不影响业务
        }
    }
}
