package com.campus.ai.trace.store;

/** 追踪 session 行已被保留策略删除；writer 遇到时跳过而不是无限重试 */
public class TraceSessionMissingException extends RuntimeException {

    public TraceSessionMissingException(String sessionId) {
        super("trace session missing: " + sessionId);
    }
}
