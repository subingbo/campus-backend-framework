package com.campus.ai.trace.domain;

import java.util.Set;

/**
 * 事件命名标准 domain/action（计划 §3.1）。
 * 只使用稳定常量；新增事件必须先更新 docs/agent-trace/events-v1.md。
 */
public final class AgentEventType {

    public static final String SESSION_START = "session/start";
    public static final String SESSION_END = "session/end";

    public static final String TURN_START = "turn/start";
    public static final String TURN_END = "turn/end";

    public static final String STEP_START = "step/start";
    public static final String STEP_END = "step/end";

    public static final String LLM_REQUEST = "llm/request";
    public static final String LLM_RESPONSE = "llm/response";
    public static final String LLM_ERROR = "llm/error";
    /** P0 LlmClient 没有重试；类型保留给后续接入重试的实现 */
    public static final String LLM_RETRY = "llm/retry";

    public static final String TOOL_CALL = "tool/call";
    public static final String TOOL_RESULT = "tool/result";
    public static final String TOOL_ERROR = "tool/error";

    public static final String MAP_SEARCH = "map/search";
    public static final String MAP_PERMISSION_FILTER = "map/permission-filter";
    public static final String PLACE_SEMANTIC_RETRIEVAL = "place/semantic-retrieval";
    public static final String ROUTE_ESTIMATE = "route/estimate";
    public static final String SCHEDULE_FREE_WINDOW = "schedule/free-window";

    public static final String PLAN_DRAFT = "plan/draft";
    public static final String PLAN_REVISE = "plan/revise";
    public static final String PLAN_VALIDATE = "plan/validate";
    public static final String PLAN_CONFIRM = "plan/confirm";
    public static final String ACTIVITY_CREATED = "activity/created";
    public static final String PLAN_SAVE_ERROR = "plan/save-error";

    public static final String STREAM_OPEN = "stream/open";
    public static final String STREAM_CLOSE = "stream/close";
    public static final String STREAM_ERROR = "stream/error";

    public static final String TRACE_DROPPED = "trace/dropped";
    public static final String TRACE_PARTIAL = "trace/partial";

    /** 导出首行元信息；不会作为真实事件落库 */
    public static final String TRACE_META = "trace/meta";

    public static final Set<String> ALL = Set.of(
            SESSION_START, SESSION_END,
            TURN_START, TURN_END,
            STEP_START, STEP_END,
            LLM_REQUEST, LLM_RESPONSE, LLM_ERROR, LLM_RETRY,
            TOOL_CALL, TOOL_RESULT, TOOL_ERROR,
            MAP_SEARCH, MAP_PERMISSION_FILTER, PLACE_SEMANTIC_RETRIEVAL,
            ROUTE_ESTIMATE, SCHEDULE_FREE_WINDOW,
            PLAN_DRAFT, PLAN_REVISE, PLAN_VALIDATE, PLAN_CONFIRM,
            ACTIVITY_CREATED, PLAN_SAVE_ERROR,
            STREAM_OPEN, STREAM_CLOSE, STREAM_ERROR,
            TRACE_DROPPED, TRACE_PARTIAL
    );

    private AgentEventType() {
    }
}
