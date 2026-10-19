package com.campus.ai.trace.service;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.domain.AgentEventType;
import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.dto.TraceSessionView;
import com.campus.ai.trace.mapper.AgentTraceEventMapper;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 MySQL 权威事件表按 seq keyset 分页生成 JSONL。
 * 每行一个合法 JSON；首行 trace/meta 标明完整性；不做 Pretty Print。
 */
@Component
public class AgentTraceExportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final AgentTraceEventMapper eventMapper;
    private final TraceAccessPolicy accessPolicy;
    private final AgentTraceProperties properties;
    private final ObjectMapper objectMapper;

    public AgentTraceExportService(AgentTraceEventMapper eventMapper,
                                   TraceAccessPolicy accessPolicy,
                                   AgentTraceProperties properties,
                                   @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.eventMapper = eventMapper;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void exportJsonl(String sessionId, Long requesterUserId, OutputStream out) throws IOException {
        if (!properties.getExport().isEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "追踪导出未启用");
        }
        AgentTraceSession session = accessPolicy.requireReadable(sessionId, requesterUserId);
        int pageSize = Math.min(1000, Math.max(50, properties.getExport().getPageSize()));
        boolean complete = TraceSessionView.isComplete(session.getStatus(), session.getDroppedCount());

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writeLine(writer, metaLine(session, complete));
        long afterSeq = 0L;
        while (true) {
            List<AgentTraceEvent> batch = eventMapper.selectExportBatch(sessionId, afterSeq, pageSize);
            if (batch.isEmpty()) {
                break;
            }
            for (AgentTraceEvent event : batch) {
                writeLine(writer, eventLine(event));
                afterSeq = event.getSeq() == null ? afterSeq : event.getSeq();
            }
            writer.flush();
            if (batch.size() < pageSize) {
                break;
            }
        }
        writer.flush();
    }

    private Map<String, Object> metaLine(AgentTraceSession session, boolean complete) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", session.getStatus());
        data.put("eventCount", session.getEventCount());
        data.put("droppedCount", session.getDroppedCount());
        data.put("complete", complete);
        data.put("model", session.getModel());
        data.put("startedAt", iso(session.getStartedAt()));
        data.put("endedAt", iso(session.getEndedAt()));
        data.put("durationMs", session.getDurationMs());
        data.put("exportedAt", LocalDateTime.now().format(ISO));

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("schemaVersion", 1);
        line.put("type", AgentEventType.TRACE_META);
        line.put("time", System.currentTimeMillis());
        line.put("sessionId", session.getId());
        line.put("data", data);
        return line;
    }

    private Map<String, Object> eventLine(AgentTraceEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("schemaVersion", 1);
        line.put("type", event.getEventType());
        line.put("sessionId", event.getSessionId());
        line.put("traceId", event.getTraceId());
        line.put("seq", event.getSeq());
        line.put("time", event.getOccurredAtMs());
        if (event.getTurnNo() != null) {
            line.put("turn", event.getTurnNo());
        }
        if (event.getStepNo() != null) {
            line.put("step", event.getStepNo());
        }
        if (event.getCallId() != null) {
            line.put("callId", event.getCallId());
        }
        if (event.getStatus() != null) {
            line.put("status", event.getStatus());
        }
        if (event.getDurationMs() != null) {
            line.put("durationMs", event.getDurationMs());
        }
        if (event.getToolName() != null) {
            line.put("tool", event.getToolName());
        }
        if (event.getErrorCode() != null) {
            line.put("errorCode", event.getErrorCode());
        }
        line.put("data", dataNode(event.getPayloadJson()));
        return line;
    }

    private JsonNode dataNode(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private void writeLine(BufferedWriter writer, Map<String, Object> line) throws IOException {
        writer.write(objectMapper.writeValueAsString(line));
        writer.write('\n');
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.format(ISO);
    }
}
