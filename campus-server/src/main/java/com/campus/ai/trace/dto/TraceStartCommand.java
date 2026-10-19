package com.campus.ai.trace.dto;

/** 创建/复用追踪会话的入参 */
public record TraceStartCommand(Long conversationId,
                                Long userId,
                                TraceTrigger trigger,
                                String provider,
                                String model) {
}
