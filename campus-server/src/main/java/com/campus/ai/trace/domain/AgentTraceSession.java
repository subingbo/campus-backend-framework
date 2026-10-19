package com.campus.ai.trace.domain;

import lombok.Data;

import java.time.LocalDateTime;

/** 一次 Agent 规划 episode 的追踪会话（agent_trace_session） */
@Data
public class AgentTraceSession {

    private String id;
    private Long userId;
    private Long conversationId;
    private Long sharedMapId;
    private Long groupId;
    private Long draftId;
    private String triggerSource;
    private String provider;
    private String model;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Integer eventCount;
    private Integer toolCallCount;
    private Integer errorCount;
    private Integer droppedCount;
    private Long nextSeq;
    private String lastErrorCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
