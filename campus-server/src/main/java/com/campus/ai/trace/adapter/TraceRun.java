package com.campus.ai.trace.adapter;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.llm.LlmClientException;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.plan.PlanValidationException;
import com.campus.ai.trace.domain.AgentEventType;
import com.campus.ai.trace.domain.TraceStatus;
import com.campus.ai.trace.dto.TraceContext;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.dto.TraceRequest;
import com.campus.ai.trace.dto.TraceTrigger;
import com.campus.ai.trace.security.TraceDataRedactor;
import com.campus.ai.trace.service.AgentTraceService;
import com.campus.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 Agent 对话 run 的追踪句柄：stream/turn/step/LLM/tool/业务摘要/终态。
 * 所有方法内部吞掉异常，绝不因为 Trace 影响主流程。
 */
public final class TraceRun {

    private static final TraceRun NOOP = new TraceRun(null, null, null, null, null, 0);

    private final AgentTraceService service;
    private final TraceDataRedactor redactor;
    private final TraceRequest request;
    private final String provider;
    private final String model;
    private final int maxTokens;
    private final long runStartNano = System.nanoTime();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private Integer currentStep = 0;
    private boolean turnOpen;
    private boolean stepOpen;
    private long stepStartNano;

    public TraceRun(AgentTraceService service,
                    TraceDataRedactor redactor,
                    TraceRequest request,
                    String provider,
                    String model,
                    int maxTokens) {
        this.service = service;
        this.redactor = redactor;
        this.request = request;
        this.provider = provider;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    public static TraceRun noop() {
        return NOOP;
    }

    public boolean isNoop() {
        return service == null || request == null;
    }

    public void open(TraceTrigger trigger, int messageLength, boolean hasUserLocation, int historyLimit) {
        if (isNoop()) {
            return;
        }
        String transport = trigger != null && TraceTrigger.GROUP_CHAT.equals(trigger.sourceOrDefault())
                ? "ws" : "sse";
        event(AgentEventType.STREAM_OPEN, TraceStatus.RUNNING, null, null, null, null,
                data("transport", transport), false);
        event(AgentEventType.TURN_START, TraceStatus.RUNNING, null, null, null, null,
                data("messageLength", messageLength,
                        "hasUserLocation", hasUserLocation,
                        "historyLimit", historyLimit), false);
        turnOpen = true;
    }

    public void stepStart(int round) {
        if (isNoop()) {
            return;
        }
        currentStep = round;
        stepOpen = true;
        stepStartNano = System.nanoTime();
        event(AgentEventType.STEP_START, TraceStatus.RUNNING, null, null, null, null,
                data("round", round), false);
    }

    public void stepEnd(String status) {
        if (isNoop() || !stepOpen) {
            return;
        }
        long duration = elapsedMs(stepStartNano);
        event(AgentEventType.STEP_END, status, null, duration, null, null,
                data("round", currentStep, "status", status), false);
        stepOpen = false;
    }

    public void llmRequest(int messageCount, int toolCount) {
        if (isNoop()) {
            return;
        }
        event(AgentEventType.LLM_REQUEST, TraceStatus.RUNNING, null, null, null, null,
                data("provider", provider,
                        "model", model,
                        "maxTokens", maxTokens,
                        "messageCount", messageCount,
                        "toolCount", toolCount,
                        "stream", true), false);
    }

    public void llmResponse(LlmResponse response, long durationMs) {
        if (isNoop() || response == null) {
            return;
        }
        List<String> toolNames = new ArrayList<>();
        if (response.toolCalls() != null) {
            for (LlmToolCall call : response.toolCalls()) {
                if (call != null && call.name() != null && !call.name().isBlank()) {
                    toolNames.add(call.name());
                }
            }
        }
        Map<String, Object> data = data("model", model,
                "finishReason", response.finishReason(),
                "toolCallCount", toolNames.size(),
                "toolNames", toolNames,
                "contentLength", response.content() == null ? 0 : response.content().length(),
                "promptTokens", response.promptTokens(),
                "completionTokens", response.completionTokens(),
                "totalTokens", response.totalTokens());
        record(AgentEventType.LLM_RESPONSE, TraceStatus.SUCCESS, null, durationMs, null, null,
                toLong(response.promptTokens()), toLong(response.completionTokens()),
                toLong(response.totalTokens()), null, data, false);
    }

    public void llmError(BusinessException ex, long durationMs, boolean cancelled) {
        if (isNoop()) {
            return;
        }
        String errorType = "LLM_ERROR";
        Integer httpStatus = null;
        if (ex instanceof LlmClientException clientException) {
            errorType = clientException.getErrorType();
            httpStatus = clientException.getHttpStatus();
        } else if (cancelled) {
            errorType = "LLM_CANCELLED";
        }
        event(AgentEventType.LLM_ERROR, TraceStatus.FAILED, null, durationMs, null, errorType,
                data("errorType", errorType, "httpStatus", httpStatus, "cancelled", cancelled), false);
    }

    public void toolCall(String callId, String tool, String argsJson) {
        if (isNoop()) {
            return;
        }
        event(AgentEventType.TOOL_CALL, TraceStatus.RUNNING, callId, null, tool, null,
                data("input", redactor.toolInput(tool, argsJson)), false);
    }

    public void toolResult(String callId, String tool, String resultJson, long durationMs, boolean reused) {
        if (isNoop()) {
            return;
        }
        event(AgentEventType.TOOL_RESULT, TraceStatus.SUCCESS, callId, durationMs, tool, null,
                data("output", redactor.toolOutput(tool, resultJson), "reused", reused), false);
        for (TraceDataRedactor.ToolEvent business : redactor.businessEvents(tool, resultJson)) {
            event(business.type(), TraceStatus.SUCCESS, callId, durationMs, tool, null,
                    business.data(), false);
        }
    }

    /** 工具返回错误 JSON（AgentToolkit 内部捕获异常后的统一形态） */
    public void toolError(String callId, String tool, String resultJson, long durationMs) {
        if (isNoop()) {
            return;
        }
        String errorCode = redactor.classifyToolError(resultJson);
        event(AgentEventType.TOOL_ERROR, TraceStatus.FAILED, callId, durationMs, tool, errorCode,
                data("reused", false), false);
        if (redactor.isPermissionDenied(resultJson)) {
            event(AgentEventType.MAP_PERMISSION_FILTER, TraceStatus.FAILED, callId, null, tool,
                    "PERMISSION_DENIED",
                    data("operation", tool, "passed", false, "reason", "PERMISSION_DENIED"), false);
        }
    }

    /** 未真实执行就被拒绝的调用（重复参数/搜索预算耗尽） */
    public void toolRejected(String callId, String tool, String errorCode, long durationMs) {
        if (isNoop()) {
            return;
        }
        event(AgentEventType.TOOL_ERROR, TraceStatus.FAILED, callId, durationMs, tool, errorCode,
                data("reused", "duplicate_call".equals(errorCode)), false);
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
        event(AgentEventType.PLAN_VALIDATE, TraceStatus.SUCCESS, null, null, null, null, data, false);
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
        event(AgentEventType.PLAN_VALIDATE, TraceStatus.FAILED, null, null, null, ex.getRuleId(),
                data, false);
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
        event(AgentEventType.PLAN_VALIDATE, TraceStatus.FAILED, null, null, null, ruleId, data, false);
    }

    public void draft(PlanDto plan, int draftVersion, Long draftId) {
        if (isNoop()) {
            return;
        }
        Map<String, Object> data = planSummary(plan);
        data.put("draftId", draftId);
        data.put("draftVersion", draftVersion);
        record(AgentEventType.PLAN_DRAFT, TraceStatus.SUCCESS, null, null, null, null,
                null, null, null, draftId, data, false);
    }

    /**
     * run 的 finally 调用；保证 turn/end + stream/close。
     *
     * @param outcome DRAFT/ANSWERED/ERROR/CANCELLED/BUDGET_EXCEEDED
     * @param cause   停止原因（round_start/llm_error/empty_output/rounds_budget/draft/answer...）
     */
    public void finish(String outcome, String cause, int rounds, int toolCount, boolean hasDraft) {
        if (isNoop() || closed.get()) {
            return;
        }
        String terminal = terminalStatus(outcome);
        if (stepOpen) {
            stepEnd(TraceStatus.CANCELLED.equals(terminal) ? TraceStatus.CANCELLED
                    : TraceStatus.SUCCESS.equals(terminal) ? TraceStatus.SUCCESS : TraceStatus.FAILED);
        }
        if (!turnOpen) {
            closeInternal();
            return;
        }
        long duration = elapsedMs(runStartNano);
        event(AgentEventType.TURN_END, terminal, null, duration, null, null,
                data("outcome", outcome, "reason", cause, "rounds", rounds,
                        "toolCallCount", toolCount, "hasDraft", hasDraft, "durationMs", duration), true);
        if ("ERROR".equals(outcome) || "BUDGET_EXCEEDED".equals(outcome)) {
            event(AgentEventType.STREAM_ERROR, TraceStatus.FAILED, null, duration, null, outcome,
                    data("clientCancelled", false, "errorCode", outcome), true);
        }
        boolean cancelled = TraceStatus.CANCELLED.equals(terminal);
        event(AgentEventType.STREAM_CLOSE, terminal, null, duration, null, null,
                data("closeReason", cancelled ? "client_disconnected" : cause,
                        "cancelled", cancelled, "durationMs", duration), true);
        closeInternal();
    }

    public void close() {
        finish("ANSWERED", "implicit_end", 0, 0, false);
    }

    private void closeInternal() {
        if (closed.compareAndSet(false, true)) {
            try {
                service.onRunClosed(request.context().sessionId());
            } catch (Exception ignored) {
                // Trace 生命周期必须吞异常
            }
        }
    }

    private String terminalStatus(String outcome) {
        return switch (outcome == null ? "" : outcome) {
            case "DRAFT", "ANSWERED" -> TraceStatus.SUCCESS;
            case "CANCELLED" -> TraceStatus.CANCELLED;
            default -> TraceStatus.FAILED;
        };
    }

    private Map<String, Object> planSummary(PlanDto plan) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (plan == null) {
            return data;
        }
        put(data, "stopCount", plan.getStops() == null ? 0 : plan.getStops().size());
        put(data, "selectedPlaceIds", plan.getSelectedPlaceIds());
        put(data, "sourceMapIds", plan.getSourceMapIds());
        put(data, "groupId", plan.getGroupId());
        put(data, "constraintStatus", plan.getConstraintStatus());
        put(data, "totalTravelMinutes", plan.getTotalTravelMinutes());
        put(data, "routeConfidence", plan.getRouteConfidence());
        put(data, "hasReferenceMap", plan.getReferenceMapId() != null);
        return data;
    }

    private void event(String type, String status, String callId, Long durationMs,
                       String tool, String errorCode, Map<String, Object> data, boolean critical) {
        record(type, status, callId, durationMs, tool, errorCode,
                null, null, null, null, data, critical);
    }

    private void record(String type, String status, String callId, Long durationMs,
                        String tool, String errorCode,
                        Long promptTokens, Long completionTokens, Long totalTokens,
                        Long draftId, Map<String, Object> data, boolean critical) {
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
                    .step(currentStep)
                    .type(type)
                    .callId(callId)
                    .status(status)
                    .durationMs(durationMs)
                    .toolName(tool)
                    .errorCode(errorCode)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .draftId(draftId)
                    .data(data == null ? Map.of() : data)
                    .critical(critical)
                    .build());
        } catch (Exception ignored) {
            // 追踪绝不影响业务
        }
    }

    private long elapsedMs(long startNano) {
        return Math.max(0L, (System.nanoTime() - startNano) / 1_000_000L);
    }

    private Long toLong(Integer value) {
        return value == null ? null : value.longValue();
    }

    private Map<String, Object> data(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String key = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value != null) {
                map.put(key, value);
            }
        }
        return map;
    }

    private void put(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
