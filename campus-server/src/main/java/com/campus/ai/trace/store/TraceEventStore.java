package com.campus.ai.trace.store;

import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.dto.TraceEventRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trace 持久化边界：每个方法使用独立的 REQUIRES_NEW 事务，业务事务回滚不会影响追踪数据。
 * 抽成接口便于 queue/writer 单测注入 fake。
 */
public interface TraceEventStore {

    void createSession(AgentTraceSession session);

    void persist(TraceEventRecord record);

    /** 队列满/写入失败时补一条 trace/partial，并累加 dropped_count */
    void persistPartial(String sessionId, long droppedCount);

    /** 陈旧 RUNNING 标记为 INTERRUPTED；写 trace/partial(inferred=true)，不伪造 session/end */
    void markInterrupted(String sessionId);

    List<String> findStaleRunning(LocalDateTime cutoff, int limit);

    int deleteOlderThan(LocalDateTime cutoff, int limit);

    void bindDraft(String sessionId, Long draftId);

    void bindContext(String sessionId, Long sharedMapId, Long groupId);
}
