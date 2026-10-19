package com.campus.ai.trace.dto;

/** 一次请求/执行尝试的上下文；显式传参，不依赖 ThreadLocal 自动传播 */
public record TraceContext(String sessionId,
                           String traceId,
                           Long userId,
                           Long conversationId,
                           Integer turn,
                           Integer step) {

    public TraceContext withStep(Integer step) {
        return new TraceContext(sessionId, traceId, userId, conversationId, turn, step);
    }

    public boolean isNoop() {
        return sessionId == null || sessionId.isBlank();
    }
}
