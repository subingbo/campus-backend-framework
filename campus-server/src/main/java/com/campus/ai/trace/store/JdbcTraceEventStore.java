package com.campus.ai.trace.store;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.domain.AgentEventType;
import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.domain.TraceStatus;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.mapper.AgentTraceEventMapper;
import com.campus.ai.trace.mapper.AgentTraceSessionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MyBatis 实现：seq 在事务内通过 session 行锁分配，事件、计数、终态在同一事务提交。
 * 与业务事务完全隔离（REQUIRES_NEW），避免 trace 失败回滚活动保存。
 */
@Slf4j
@Component
public class JdbcTraceEventStore implements TraceEventStore {

    private final AgentTraceSessionMapper sessionMapper;
    private final AgentTraceEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;
    private final int maxPayloadBytes;

    public JdbcTraceEventStore(AgentTraceSessionMapper sessionMapper,
                               AgentTraceEventMapper eventMapper,
                               @Qualifier("redisObjectMapper") ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager,
                               AgentTraceProperties properties) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxPayloadBytes = Math.max(256, properties.getMaxPayloadBytes());
    }

    @Override
    public void createSession(AgentTraceSession session) {
        tx.executeWithoutResult(status -> sessionMapper.insert(session));
    }

    @Override
    public void persist(TraceEventRecord record) {
        String payloadJson = payloadJson(record.getData());
        tx.executeWithoutResult(status -> {
            insertWithSeq(record, payloadJson);
        });
    }

    @Override
    public void persistPartial(String sessionId, long droppedCount) {
        if (droppedCount <= 0) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reason", "queue_full");
        data.put("droppedCount", droppedCount);
        TraceEventRecord record = TraceEventRecord.builder()
                .sessionId(sessionId)
                .traceId("00000000-0000-0000-0000-000000000000")
                .occurredAtMs(System.currentTimeMillis())
                .type(AgentEventType.TRACE_PARTIAL)
                .status(TraceStatus.PARTIAL)
                .data(data)
                .critical(true)
                .build();
        tx.executeWithoutResult(status -> {
            insertWithSeq(record, payloadJson(data));
            sessionMapper.incrementDropped(sessionId, droppedCount);
        });
    }

    @Override
    public void markInterrupted(String sessionId) {
        tx.executeWithoutResult(status -> {
            Long next = sessionMapper.selectNextSeqForUpdate(sessionId);
            if (next == null) {
                return;
            }
            AgentTraceSession session = sessionMapper.selectById(sessionId);
            if (session == null || !TraceStatus.RUNNING.equals(session.getStatus())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            long seq = next + 1;
            sessionMapper.allocateSeq(sessionId, seq);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "stale_running");
            data.put("inferred", true);
            AgentTraceEvent event = new AgentTraceEvent();
            event.setSessionId(sessionId);
            event.setTraceId("00000000-0000-0000-0000-000000000000");
            event.setSeq(seq);
            event.setEventType(AgentEventType.TRACE_PARTIAL);
            event.setOccurredAtMs(System.currentTimeMillis());
            event.setStatus(TraceStatus.PARTIAL);
            event.setToolName(null);
            event.setErrorCode(null);
            event.setPayloadJson(payloadJson(data));
            eventMapper.insert(event);

            sessionMapper.finish(sessionId, TraceStatus.INTERRUPTED, now, durationMs(session, now),
                    "INTERRUPTED_STALE");
        });
    }

    @Override
    public List<String> findStaleRunning(LocalDateTime cutoff, int limit) {
        return sessionMapper.selectStaleRunning(cutoff, Math.max(1, limit));
    }

    @Override
    public int deleteOlderThan(LocalDateTime cutoff, int limit) {
        Integer deleted = tx.execute(status -> sessionMapper.deleteOlderThan(cutoff, Math.max(1, limit)));
        return deleted == null ? 0 : deleted;
    }

    @Override
    public void bindDraft(String sessionId, Long draftId) {
        if (draftId == null) {
            return;
        }
        tx.executeWithoutResult(status -> sessionMapper.bindDraft(sessionId, draftId));
    }

    @Override
    public void bindContext(String sessionId, Long sharedMapId, Long groupId) {
        if (sharedMapId == null && groupId == null) {
            return;
        }
        tx.executeWithoutResult(status -> sessionMapper.bindContext(sessionId, sharedMapId, groupId));
    }

    private void insertWithSeq(TraceEventRecord record, String payloadJson) {
        Long next = sessionMapper.selectNextSeqForUpdate(record.getSessionId());
        if (next == null) {
            throw new TraceSessionMissingException(record.getSessionId());
        }
        long seq = next + 1;
        sessionMapper.allocateSeq(record.getSessionId(), seq);

        AgentTraceEvent event = new AgentTraceEvent();
        event.setSessionId(record.getSessionId());
        event.setTraceId(record.getTraceId());
        event.setSeq(seq);
        event.setEventType(record.getType());
        event.setOccurredAtMs(record.getOccurredAtMs());
        event.setTurnNo(record.getTurn());
        event.setStepNo(record.getStep());
        event.setCallId(record.getCallId());
        event.setStatus(record.getStatus());
        event.setDurationMs(record.getDurationMs());
        event.setToolName(record.getToolName());
        event.setErrorCode(record.getErrorCode());
        event.setPayloadJson(payloadJson);
        eventMapper.insert(event);

        if (AgentEventType.TOOL_CALL.equals(record.getType())) {
            sessionMapper.incrementToolCall(record.getSessionId());
        }
        if (record.getErrorCode() != null) {
            sessionMapper.incrementError(record.getSessionId());
        }
        if (AgentEventType.LLM_RESPONSE.equals(record.getType())
                && (record.getPromptTokens() != null
                || record.getCompletionTokens() != null
                || record.getTotalTokens() != null)) {
            sessionMapper.addTokens(record.getSessionId(),
                    record.getPromptTokens(), record.getCompletionTokens(), record.getTotalTokens());
        }
        if (record.getDraftId() != null) {
            sessionMapper.bindDraft(record.getSessionId(), record.getDraftId());
        }
        if (AgentEventType.SESSION_END.equals(record.getType())) {
            LocalDateTime now = LocalDateTime.now();
            String status = record.getStatus() == null ? TraceStatus.COMPLETED : record.getStatus();
            sessionMapper.finish(record.getSessionId(), status, now,
                    record.getDurationMs(), record.getErrorCode());
        }
    }

    private String payloadJson(Map<String, Object> data) {
        Map<String, Object> safe = data == null || data.isEmpty() ? Map.of() : data;
        try {
            String json = objectMapper.writeValueAsString(safe);
            if (json.getBytes(StandardCharsets.UTF_8).length > maxPayloadBytes) {
                return "{\"truncated\":true,\"originalBytes\":"
                        + json.getBytes(StandardCharsets.UTF_8).length + "}";
            }
            return json;
        } catch (Exception ex) {
            log.warn("Trace payload 序列化失败（仅保留标记）：{}", ex.getClass().getSimpleName());
            return "{\"serializationError\":true}";
        }
    }

    private long durationMs(AgentTraceSession session, LocalDateTime now) {
        if (session.getStartedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(session.getStartedAt(), now).toMillis());
    }
}
