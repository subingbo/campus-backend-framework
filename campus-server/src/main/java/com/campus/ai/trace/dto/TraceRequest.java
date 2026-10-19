package com.campus.ai.trace.dto;

/** beginRequest 的返回值：上下文 + 是否新建 session + 上一轮/下一轮 */
public record TraceRequest(TraceContext context,
                           boolean created,
                           int lastTurn,
                           int nextTurn) {
}
