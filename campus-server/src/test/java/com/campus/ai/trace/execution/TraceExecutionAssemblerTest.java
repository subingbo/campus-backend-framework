package com.campus.ai.trace.execution;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 执行链组装：只根据真实事件建立节点，缺失/重复/未知数据必须显式诊断。 */
class TraceExecutionAssemblerTest {

    private static final String SESSION = "session-1";
    private static final String CHAT = "trace-chat";
    private static final TraceExecutionAssembler ASSEMBLER =
            new TraceExecutionAssembler(new ObjectMapper());

    @Test
    void assemblesChatToolLlmAndPlanChain() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null,
                        "{\"trigger\":\"chat\",\"model\":\"qwen-plus\"}"),
                event(2, CHAT, "stream/open", 1, 0, null, null, "RUNNING", null,
                        "{\"transport\":\"sse\"}"),
                event(3, CHAT, "turn/start", 1, 0, null, null, "RUNNING", null,
                        "{\"messageLength\":12}"),
                event(4, CHAT, "step/start", 1, 1, null, null, "RUNNING", null, "{}"),
                event(5, CHAT, "llm/request", 1, 1, null, null, "RUNNING", null,
                        "{\"model\":\"qwen-plus\",\"messageCount\":3,\"toolCount\":2}"),
                event(6, CHAT, "llm/response", 1, 1, null, null, "SUCCESS", 100,
                        "{\"model\":\"qwen-plus\",\"finishReason\":\"tool_calls\","
                                + "\"toolNames\":[\"search_places\"],\"promptTokens\":10,"
                                + "\"completionTokens\":5,\"totalTokens\":15}"),
                event(7, CHAT, "tool/call", 1, 1, "call_1", "search_places", "RUNNING", null,
                        "{\"input\":{\"keywordLength\":3}}"),
                event(8, CHAT, "tool/result", 1, 1, "call_1", "search_places", "SUCCESS", 20,
                        "{\"output\":{\"resultCount\":2},\"reused\":false}"),
                event(9, CHAT, "map/search", 1, 1, "call_1", "search_places", "SUCCESS", 20,
                        "{\"source\":\"CAMPUS_LIB\",\"resultCount\":2}"),
                event(10, CHAT, "plan/validate", 1, 1, null, null, "SUCCESS", null,
                        "{\"phase\":\"SUBMIT\",\"passed\":true,\"draftVersion\":1}"),
                event(11, CHAT, "plan/draft", 1, 1, null, null, "SUCCESS", null,
                        "{\"draftId\":129,\"draftVersion\":1,\"stopCount\":2}"),
                event(12, CHAT, "step/end", 1, 1, null, null, "SUCCESS", 200, "{}"),
                event(13, CHAT, "turn/end", 1, 1, null, null, "SUCCESS", 300, "{}"),
                event(14, CHAT, "stream/close", 1, 1, null, null, "SUCCESS", 300,
                        "{\"closeReason\":\"draft\"}"),
                event(15, CHAT, "session/end", 0, null, null, null, "COMPLETED", 400,
                        "{\"reason\":\"plan_saved\"}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        List<TraceExecutionNode> flat = flatten(assembly);

        assertThat(assembly.sessionStatus()).isEqualTo("COMPLETED");
        assertThat(assembly.diagnostics().eventCount()).isEqualTo(15);
        assertThat(assembly.diagnostics().seqContiguous()).isTrue();
        assertThat(assembly.diagnostics().missingStartCount()).isZero();
        assertThat(assembly.diagnostics().missingEndCount()).isZero();
        assertThat(assembly.diagnostics().unknownTypeCount()).isZero();

        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.STREAM);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.TURN);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.STEP);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.LLM_CALL);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.TOOL_CALL);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.MAP_SEARCH);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.PLAN_VALIDATE);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.PLAN_DRAFT);

        TraceExecutionNode llm = first(flat, TraceExecutionNodeKind.LLM_CALL);
        assertThat(llm.hasStart()).isTrue();
        assertThat(llm.hasEnd()).isTrue();
        assertThat(llm.durationMs()).isEqualTo(100L);
        assertThat(llm.attributes()).containsEntry("model", "qwen-plus")
                .containsEntry("promptTokens", 10L);

        TraceExecutionNode tool = first(flat, TraceExecutionNodeKind.TOOL_CALL);
        assertThat(tool.callId()).isEqualTo("call_1");
        assertThat(tool.hasStart()).isTrue();
        assertThat(tool.hasEnd()).isTrue();
        assertThat(tool.durationMs()).isEqualTo(20L);
        assertThat(tool.children())
                .extracting(TraceExecutionNode::kind)
                .contains(TraceExecutionNodeKind.MAP_SEARCH);
    }

    @Test
    void missingToolEndRemainsIncompleteAndDoesNotBecomeSuccess() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"),
                event(2, CHAT, "turn/start", 1, 0, null, null, "RUNNING", null, "{}"),
                event(3, CHAT, "step/start", 1, 1, null, null, "RUNNING", null, "{}"),
                event(4, CHAT, "tool/call", 1, 1, "call_1", "route_info", "RUNNING", null,
                        "{\"mode\":\"WALK\"}"),
                event(5, CHAT, "step/end", 1, 1, null, null, "SUCCESS", 100, "{}"),
                event(6, CHAT, "turn/end", 1, 1, null, null, "SUCCESS", 100, "{}"),
                event(7, CHAT, "session/end", 0, null, null, null, "COMPLETED", 100, "{}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        TraceExecutionNode tool = first(flatten(assembly), TraceExecutionNodeKind.TOOL_CALL);

        assertThat(tool.hasStart()).isTrue();
        assertThat(tool.hasEnd()).isFalse();
        assertThat(tool.status()).isEqualTo("RUNNING");
        assertThat(tool.warnings()).contains("MISSING_END");
        assertThat(assembly.diagnostics().missingEndCount()).isEqualTo(1);
    }

    @Test
    void llmResponseWithoutRequestIsMissingStart() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"),
                event(2, CHAT, "turn/start", 1, 0, null, null, "RUNNING", null, "{}"),
                event(3, CHAT, "step/start", 1, 1, null, null, "RUNNING", null, "{}"),
                event(4, CHAT, "llm/response", 1, 1, null, null, "SUCCESS", 50,
                        "{\"model\":\"qwen-plus\",\"finishReason\":\"stop\"}"),
                event(5, CHAT, "step/end", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(6, CHAT, "turn/end", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(7, CHAT, "session/end", 0, null, null, null, "COMPLETED", 50, "{}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        TraceExecutionNode llm = first(flatten(assembly), TraceExecutionNodeKind.LLM_CALL);

        assertThat(llm.hasStart()).isFalse();
        assertThat(llm.hasEnd()).isTrue();
        assertThat(assembly.diagnostics().missingStartCount()).isEqualTo(1);
    }

    @Test
    void duplicateCallIdAndRepeatedToolNamesAreNotMerged() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"),
                event(2, CHAT, "step/start", 1, 1, null, null, "RUNNING", null, "{}"),
                event(3, CHAT, "tool/call", 1, 1, "call_1", "route_info", "RUNNING", null, "{}"),
                event(4, CHAT, "tool/result", 1, 1, "call_1", "route_info", "SUCCESS", 10, "{}"),
                event(5, CHAT, "tool/call", 1, 1, "call_1", "route_info", "RUNNING", null, "{}"),
                event(6, CHAT, "tool/result", 1, 1, "call_1", "route_info", "SUCCESS", 11, "{}"),
                event(7, CHAT, "step/end", 1, 1, null, null, "SUCCESS", 30, "{}"),
                event(8, CHAT, "session/end", 0, null, null, null, "COMPLETED", 30, "{}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        List<TraceExecutionNode> tools = flatten(assembly).stream()
                .filter(node -> node.kind() == TraceExecutionNodeKind.TOOL_CALL)
                .toList();

        assertThat(tools).hasSize(2);
        assertThat(tools).allMatch(TraceExecutionNode::hasEnd);
        assertThat(tools.get(0).nodeId()).isNotEqualTo(tools.get(1).nodeId());
        assertThat(assembly.diagnostics().duplicateCallIdCount()).isEqualTo(1);
        assertThat(assembly.diagnostics().warnings()).contains("DUPLICATE_CALL_ID");
    }

    @Test
    void saveAndReviseAreSeparateRunsFromChatStep() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"),
                event(2, CHAT, "stream/open", 1, 0, null, null, "RUNNING", null, "{}"),
                event(3, CHAT, "turn/start", 1, 0, null, null, "RUNNING", null, "{}"),
                event(4, CHAT, "step/start", 1, 1, null, null, "RUNNING", null, "{}"),
                event(5, CHAT, "llm/request", 1, 1, null, null, "RUNNING", null, "{}"),
                event(6, CHAT, "llm/response", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(7, CHAT, "step/end", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(8, CHAT, "turn/end", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(9, CHAT, "stream/close", 1, 1, null, null, "SUCCESS", 50, "{}"),
                event(10, "trace-save", "plan/confirm", 1, null, null, null, "RUNNING", null,
                        "{\"draftVersion\":1,\"operation\":\"CREATE\"}"),
                event(11, "trace-save", "plan/validate", 1, null, null, null, "SUCCESS", null,
                        "{\"phase\":\"SAVE\",\"draftVersion\":1,\"passed\":true}"),
                event(12, "trace-save", "plan/confirm", 1, null, null, null, "SUCCESS", 20,
                        "{\"draftVersion\":1,\"operation\":\"CREATE\",\"activityId\":22}"),
                event(13, "trace-save", "activity/created", 1, null, null, null, "SUCCESS", null,
                        "{\"draftVersion\":1,\"operation\":\"CREATE\",\"activityId\":22}"),
                event(14, "trace-revise", "plan/validate", 1, null, null, null, "SUCCESS", null,
                        "{\"phase\":\"REVISE\",\"draftVersion\":1,\"passed\":true}"),
                event(15, "trace-revise", "plan/revise", 1, null, null, null, "SUCCESS", null,
                        "{\"fromVersion\":1,\"toVersion\":2,\"changed\":true}"),
                event(16, CHAT, "session/end", 0, null, null, null, "COMPLETED", 100, "{}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        List<TraceExecutionNode> runs = flatten(assembly).stream()
                .filter(node -> node.kind() == TraceExecutionNodeKind.RUN)
                .toList();

        assertThat(runs).hasSize(3);
        TraceExecutionNode saveRun = runs.stream()
                .filter(run -> "trace-save".equals(run.traceId())).findFirst().orElseThrow();
        TraceExecutionNode reviseRun = runs.stream()
                .filter(run -> "trace-revise".equals(run.traceId())).findFirst().orElseThrow();
        assertThat(flatten(saveRun)).extracting(TraceExecutionNode::kind)
                .contains(TraceExecutionNodeKind.PLAN_CONFIRM,
                        TraceExecutionNodeKind.PLAN_VALIDATE,
                        TraceExecutionNodeKind.ACTIVITY_CREATED)
                .doesNotContain(TraceExecutionNodeKind.STEP);
        assertThat(flatten(reviseRun)).extracting(TraceExecutionNode::kind)
                .contains(TraceExecutionNodeKind.PLAN_VALIDATE,
                        TraceExecutionNodeKind.PLAN_REVISE);
        assertThat(flatten(assembly)).filteredOn(node -> node.kind() == TraceExecutionNodeKind.TOOL_CALL)
                .isEmpty();
    }

    @Test
    void unknownGappedSensitiveAndOrphanEventsAreDiagnosedWithoutLeak() {
        List<AgentTraceEvent> events = List.of(
                event(1, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"),
                event(3, CHAT, "foo/bar", 1, 0, null, null, "RUNNING", null,
                        "{\"name\":\"图书馆\",\"latitude\":23.04,\"message\":\"秘密\",\"count\":2}"),
                event(4, CHAT, "map/search", 1, 1, null, null, "SUCCESS", 5,
                        "{\"source\":\"CAMPUS_LIB\",\"resultCount\":2}"),
                event(5, CHAT, "llm/response", 1, 1, null, null, "SUCCESS", 50,
                        "{\"model\":\"qwen-plus\"}"),
                event(6, CHAT, "trace/partial", 0, null, null, null, "PARTIAL", null,
                        "{\"reason\":\"queue_full\",\"droppedCount\":2}"),
                event(7, CHAT, "session/end", 0, null, null, null, "COMPLETED", 100, "{}")
        );

        TraceExecutionAssembly assembly = ASSEMBLER.assemble(events);
        List<TraceExecutionNode> flat = flatten(assembly);

        assertThat(assembly.diagnostics().seqContiguous()).isFalse();
        assertThat(assembly.diagnostics().seqGaps()).hasSize(1);
        assertThat(assembly.diagnostics().unknownTypeCount()).isEqualTo(1);
        assertThat(assembly.diagnostics().droppedCount()).isEqualTo(2);
        assertThat(assembly.diagnostics().orphanBusinessCount()).isEqualTo(1);
        assertThat(assembly.diagnostics().missingStartCount()).isPositive();
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.UNKNOWN);
        assertThat(flat).anyMatch(node -> node.kind() == TraceExecutionNodeKind.INTEGRITY);
        assertThat(flat).filteredOn(node -> node.kind() == TraceExecutionNodeKind.UNKNOWN)
                .allSatisfy(node -> {
                    assertThat(node.attributes()).doesNotContainKeys("name", "latitude", "message");
                    assertThat(node.attributes().toString())
                            .doesNotContain("图书馆", "23.04", "秘密");
                });
    }

    @Test
    void tenThousandEventsAssembleStablyAndOnce() {
        List<AgentTraceEvent> events = new ArrayList<>();
        long seq = 1;
        events.add(event(seq++, CHAT, "session/start", 0, 0, null, null, "RUNNING", null, "{}"));
        events.add(event(seq++, CHAT, "turn/start", 1, 0, null, null, "RUNNING", null, "{}"));
        for (int step = 1; step <= 2500; step++) {
            String callId = "call_" + step;
            events.add(event(seq++, CHAT, "step/start", 1, step, null, null, "RUNNING", null, "{}"));
            events.add(event(seq++, CHAT, "tool/call", 1, step, callId, "route_info",
                    "RUNNING", null, "{}"));
            events.add(event(seq++, CHAT, "tool/result", 1, step, callId, "route_info",
                    "SUCCESS", 2, "{}"));
            events.add(event(seq++, CHAT, "step/end", 1, step, null, null, "SUCCESS", 3, "{}"));
        }
        long endSeq = seq;
        events.add(event(endSeq++, CHAT, "turn/end", 1, 1, null, null, "SUCCESS", 100, "{}"));
        events.add(event(endSeq, CHAT, "session/end", 0, null, null, null,
                "COMPLETED", 100, "{}"));

        long started = System.nanoTime();
        TraceExecutionAssembly first = ASSEMBLER.assemble(events);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        List<TraceExecutionNode> flat = flatten(first);
        assertThat(first.diagnostics().eventCount()).isEqualTo(events.size());
        assertThat(first.diagnostics().seqContiguous()).isTrue();
        assertThat(first.diagnostics().missingStartCount()).isZero();
        assertThat(first.diagnostics().missingEndCount()).isZero();
        assertThat(flat.stream().filter(node -> node.kind() == TraceExecutionNodeKind.STEP))
                .hasSize(2500);
        assertThat(flat.stream().filter(node -> node.kind() == TraceExecutionNodeKind.TOOL_CALL))
                .hasSize(2500);
        assertThat(flat.stream().map(TraceExecutionNode::nodeId).distinct().count())
                .isEqualTo(flat.size());
        assertThat(elapsedMs).isLessThan(10_000L);

        TraceExecutionAssembly second = ASSEMBLER.assemble(events);
        assertThat(second.roots().toString()).isEqualTo(first.roots().toString());
        assertThat(second.diagnostics()).isEqualTo(first.diagnostics());
    }

    private AgentTraceEvent event(long seq, String traceId, String type, Integer turn,
                                  Integer step, String callId, String tool, String status,
                                  Number duration, String payload) {
        AgentTraceEvent event = new AgentTraceEvent();
        event.setId(seq);
        event.setSessionId(SESSION);
        event.setTraceId(traceId);
        event.setSeq(seq);
        event.setEventType(type);
        event.setOccurredAtMs(seq * 1000L);
        event.setTurnNo(turn);
        event.setStepNo(step);
        event.setCallId(callId);
        event.setToolName(tool);
        event.setStatus(status);
        event.setDurationMs(duration == null ? null : duration.longValue());
        event.setPayloadJson(payload);
        return event;
    }

    private TraceExecutionNode first(List<TraceExecutionNode> nodes, TraceExecutionNodeKind kind) {
        return nodes.stream().filter(node -> node.kind() == kind).findFirst().orElseThrow();
    }

    private List<TraceExecutionNode> flatten(TraceExecutionAssembly assembly) {
        List<TraceExecutionNode> result = new ArrayList<>();
        for (TraceExecutionNode root : assembly.roots()) {
            flatten(root, result);
        }
        return result;
    }

    private List<TraceExecutionNode> flatten(TraceExecutionNode node) {
        List<TraceExecutionNode> result = new ArrayList<>();
        flatten(node, result);
        return result;
    }

    private void flatten(TraceExecutionNode node, List<TraceExecutionNode> result) {
        result.add(node);
        for (TraceExecutionNode child : node.children()) {
            flatten(child, result);
        }
    }
}
