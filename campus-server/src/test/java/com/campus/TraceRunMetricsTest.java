package com.campus;

import com.campus.ai.trace.adapter.TraceRun;
import com.campus.ai.trace.dto.TraceContext;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.dto.TraceRequest;
import com.campus.ai.trace.security.TraceDataRedactor;
import com.campus.ai.trace.service.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TraceRunMetricsTest {

    @Test
    void finishRegistersSessionEndBeforeClosingRun() {
        AgentTraceService service = mock(AgentTraceService.class);
        TraceDataRedactor redactor = mock(TraceDataRedactor.class);
        TraceRequest request = new TraceRequest(
                new TraceContext("session-2", "trace-2", 1L, 2L, 1, 0), true, 0, 1);
        TraceRun run = new TraceRun(service, redactor, request, "dashscope", "qwen-plus", 2048);
        run.open(com.campus.ai.trace.dto.TraceTrigger.chat(), 10, false, 12);
        run.finish("DRAFT", "draft", 3, 4, true);

        verify(service).finishSession("session-2", "SUCCESS", "draft");
        verify(service).onRunClosed("session-2");
    }

    @Test
    void runMetricsEmitsOneRedactedEventBeforeFinish() {
        AgentTraceService service = mock(AgentTraceService.class);
        TraceDataRedactor redactor = mock(TraceDataRedactor.class);
        TraceRequest request = new TraceRequest(
                new TraceContext("session-1", "trace-1", 1L, 2L, 1, 0), true, 0, 1);
        TraceRun run = new TraceRun(service, redactor, request, "dashscope", "qwen-plus", 2048);

        run.runMetrics(Map.ofEntries(
                Map.entry("rounds", 3),
                Map.entry("totalTokens", 1200),
                Map.entry("toolCallCount", 4),
                Map.entry("firstVisibleMs", 42),
                Map.entry("totalMs", 900),
                Map.entry("routeCallCount", 2),
                Map.entry("routeEstimatedCount", 0),
                Map.entry("repeatHitCount", 1),
                Map.entry("routeCacheHitCount", 1),
                Map.entry("routeCacheMissCount", 1),
                Map.entry("hasDraft", true),
                Map.entry("outcome", "DRAFT"),
                Map.entry("stopCause", "draft")
        ));

        ArgumentCaptor<TraceEventRecord> captor = ArgumentCaptor.forClass(TraceEventRecord.class);
        verify(service).record(captor.capture());
        TraceEventRecord event = captor.getValue();
        assertThat(event.getType()).isEqualTo("run/metrics");
        assertThat(event.getStatus()).isEqualTo("SUCCESS");
        assertThat(event.getData())
                .containsEntry("rounds", 3)
                .containsEntry("totalTokens", 1200)
                .containsEntry("firstVisibleMs", 42)
                .containsEntry("outcome", "DRAFT")
                .containsEntry("stopCause", "draft");
    }
}
