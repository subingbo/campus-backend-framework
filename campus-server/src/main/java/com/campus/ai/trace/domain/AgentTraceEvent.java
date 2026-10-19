package com.campus.ai.trace.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 一条持久化的追踪事件（agent_trace_event） */
@Data
public class AgentTraceEvent {

    private Long id;
    private String sessionId;
    private String traceId;
    private Long seq;
    private String eventType;
    private Long occurredAtMs;
    private LocalDateTime persistedAt;
    private Integer turnNo;
    private Integer stepNo;
    private String callId;
    private Long parentEventId;
    private String status;
    private Long durationMs;
    private String toolName;
    private String errorCode;
    private String payloadJson;
}
