package com.campus.ai.trace.execution;

/** 执行链节点类型；只由真实持久化事件组装，不凭空补造。 */
public enum TraceExecutionNodeKind {
    SESSION,
    RUN,
    STREAM,
    TURN,
    STEP,
    LLM_CALL,
    TOOL_CALL,
    MAP_SEARCH,
    PLACE_RETRIEVAL,
    ROUTE_ESTIMATE,
    SCHEDULE_WINDOW,
    PERMISSION_FILTER,
    PLAN_VALIDATE,
    PLAN_DRAFT,
    PLAN_REVISE,
    PLAN_CONFIRM,
    ACTIVITY_CREATED,
    PLAN_SAVE_ERROR,
    INTEGRITY,
    UNKNOWN
}
