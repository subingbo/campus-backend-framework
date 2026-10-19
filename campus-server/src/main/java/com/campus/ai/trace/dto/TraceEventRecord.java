package com.campus.ai.trace.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.Map;

/**
 * 不可变事件记录：业务线程构造后入队，writer 负责分配 seq 并持久化。
 * data 只能是白名单摘要；AgentTraceService 入队前还会再执行一次 sanitize。
 */
@Getter
@Builder(toBuilder = true)
public class TraceEventRecord {

    private final String sessionId;
    private final String traceId;
    /** 业务发生时间（epoch ms），在事件创建时确定，不在 DB 写入时补造 */
    private final long occurredAtMs;
    private final Integer turn;
    private final Integer step;
    private final String type;
    private final String callId;
    private final String status;
    private final Long durationMs;
    private final String toolName;
    private final String errorCode;
    private final Long promptTokens;
    private final Long completionTokens;
    private final Long totalTokens;
    /** 仅草稿/确认事件携带，用于把 session.draft_id 绑定到 ai_conversation.id */
    private final Long draftId;
    @Builder.Default
    private final Map<String, Object> data = Map.of();
    /** 关键终态在队列满时走同步 fallback */
    @Builder.Default
    private final boolean critical = false;
}
