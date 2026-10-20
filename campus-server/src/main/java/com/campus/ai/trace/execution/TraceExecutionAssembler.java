package com.campus.ai.trace.execution;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把已持久化的 Trace 事件组装成只读执行链。
 *
 * <p>规则：按 seq 排序；callId 配对工具调用；LLM 按 traceId+turn+step FIFO 配对；
 * 缺失开始/结束保留为 incomplete；未知事件保留为 UNKNOWN；不推测、不补造、不调用任何模型/工具。</p>
 */
public final class TraceExecutionAssembler {

    private static final Pattern SAFE_STRING = Pattern.compile("^[A-Za-z0-9_./:@+\\-]{1,80}$");
    private static final int MAX_DEPTH = 3;
    private static final int MAX_LIST = 20;
    private static final int MAX_ATTRIBUTES = 40;
    private static final int MAX_WARNINGS = 100;

    private static final Set<String> ATTRIBUTE_KEYS = Set.of(
            "tool", "source", "mode", "provider", "category", "status", "reason", "phase",
            "operation", "transport", "trigger", "model", "ruleId", "ruleIds", "ruleSet",
            "errorCode", "errorType", "httpStatus", "finishReason", "closeReason",
            "constraintStatus", "routeConfidence", "lastErrorCode", "toolNames", "details",
            "input", "output", "round", "rounds", "earliestStart", "hasDraft",
            "hasUserLocation", "hasOrigin", "returnToStart", "created", "complete", "estimated",
            "reused", "idempotentReuse", "inferred", "cancelled", "rolledBack", "passed",
            "changed", "truncated", "parseError", "serializationError", "newSession",
            "descriptionPresent", "keywordPresent", "hasNear", "hasReferenceMap",
            "hasSuggestions", "hasSourceMaps", "available", "kind", "estimatedSegments",
            "outcome", "stream", "historyLimit", "clientCancelled", "resultCount", "placeId",
            "mapId", "groupId", "draftId", "draftVersion", "stopCount", "selectedPlaceIds",
            "sourceMapIds", "totalTravelMinutes", "durationMinutes", "windowCount",
            "unknownCount", "solutionCount", "activityId", "expectedVersion", "fromVersion",
            "droppedCount", "eventCount", "modelCount", "promptTokens",
            "completionTokens", "totalTokens", "fromSeq", "toSeq",
            "transportMode", "availableMinutes", "requiredMinutes", "entryCount",
            "windowsTruncated"
    );

    private static final Set<String> FORBIDDEN_NESTED_KEYS = Set.of(
            "name", "title", "content", "description", "note", "notes", "address",
            "latitude", "longitude", "lng", "lat", "polyline", "prompt", "query",
            "message", "text", "keyword", "secret", "password", "authorization",
            "cookie", "apiKey", "token"
    );

    private final ObjectMapper objectMapper;

    public TraceExecutionAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public TraceExecutionAssembly assemble(List<AgentTraceEvent> input) {
        List<AgentTraceEvent> events = new ArrayList<>();
        if (input != null) {
            for (AgentTraceEvent event : input) {
                if (event != null) {
                    events.add(event);
                }
            }
        }
        events.sort(Comparator
                .comparingLong((AgentTraceEvent event) ->
                        event.getSeq() == null ? Long.MAX_VALUE : event.getSeq())
                .thenComparingLong(event -> event.getId() == null ? Long.MAX_VALUE : event.getId()));

        Diag diag = new Diag();
        String sessionId = events.stream()
                .map(AgentTraceEvent::getSessionId)
                .filter(Objects::nonNull)
                .findFirst().orElse("unknown");
        NodeAcc session = new NodeAcc("session:" + sessionId,
                TraceExecutionNodeKind.SESSION, null, null);
        session.traceId = null;
        session.linkConfidence = TraceLinkConfidence.SESSION;
        session.inferred = true;

        Map<String, RunAcc> runs = new LinkedHashMap<>();
        Long sessionEndSeq = null;
        for (AgentTraceEvent event : events) {
            String type = event.getEventType();
            if (type == null || type.isBlank()) {
                diag.unknownType++;
                diag.warn("UNKNOWN_EVENT");
                continue;
            }
            Map<String, Object> attrs = attributes(event, diag);
            switch (type) {
                case "session/start" -> {
                    session.add(event, true, false, attrs);
                    session.inferred = false;
                }
                case "session/end" -> {
                    session.add(event, false, true, attrs);
                    sessionEndSeq = event.getSeq();
                }
                case "trace/meta" -> {
                    // export-only line, never persisted as a normal event
                }
                case "trace/partial" -> {
                    diag.droppedCount += number(attrs.get("droppedCount"), 0L);
                    diag.warn("TRACE_PARTIAL");
                    NodeAcc integrity = singleNode("integrity:" + event.getSeq(),
                            TraceExecutionNodeKind.INTEGRITY, event, attrs,
                            TraceLinkConfidence.SESSION);
                    session.children.add(integrity);
                }
                default -> {
                    if (sessionEndSeq != null && event.getSeq() != null
                            && event.getSeq() > sessionEndSeq) {
                        diag.eventsAfterSessionEnd++;
                        diag.warn("EVENTS_AFTER_SESSION_END");
                    }
                    String traceId = event.getTraceId();
                    if (traceId == null || traceId.isBlank()
                            || traceId.startsWith("00000000-0000-0000-0000-")) {
                        diag.unknownType++;
                        diag.warn("UNKNOWN_EVENT");
                        session.children.add(singleNode("unknown:" + event.getSeq(),
                                TraceExecutionNodeKind.UNKNOWN, event, attrs,
                                TraceLinkConfidence.NONE));
                        continue;
                    }
                    RunAcc run = runs.computeIfAbsent(traceId,
                            key -> new RunAcc(key));
                    run.handle(event, attrs, diag);
                }
            }
        }

        for (RunAcc run : runs.values()) {
            session.children.add(run.finish(diag));
        }
        session.children.sort(Comparator.comparingLong(NodeAcc::orderSeq)
                .thenComparing(node -> node.nodeId));

        session.finish();
        if (!session.hasStart) {
            session.inferred = true;
        }

        int missingStart = 0;
        int missingEnd = 0;
        for (NodeAcc child : session.children) {
            int[] counts = countMissing(child);
            missingStart += counts[0];
            missingEnd += counts[1];
        }
        if (!session.hasStart) {
            missingStart++;
        }
        if (!session.hasEnd && !session.children.isEmpty()) {
            // 会话可以没有 session/end（chat-only / revise），不算结构性缺失；
            // 但 SESSION 节点本身仍如实标记 hasEnd=false。
        }

        List<TraceExecutionDiagnostics.SeqGap> gaps = seqGaps(events);
        Long firstSeq = events.stream().map(AgentTraceEvent::getSeq)
                .filter(Objects::nonNull).findFirst().orElse(null);
        Long lastSeq = null;
        for (int index = events.size() - 1; index >= 0; index--) {
            if (events.get(index).getSeq() != null) {
                lastSeq = events.get(index).getSeq();
                break;
            }
        }

        TraceExecutionDiagnostics diagnostics = new TraceExecutionDiagnostics(
                events.size(), firstSeq, lastSeq, gaps.isEmpty(), gaps,
                diag.droppedCount, diag.unknownType, missingStart, missingEnd,
                diag.duplicateCallId, diag.orphanBusiness, diag.eventsAfterSessionEnd,
                diag.payloadError, List.copyOf(diag.warnings));

        return new TraceExecutionAssembly(sessionId,
                session.status == null ? "UNKNOWN" : session.status,
                List.of(session.toNode()), diagnostics);
    }

    private List<TraceExecutionDiagnostics.SeqGap> seqGaps(List<AgentTraceEvent> events) {
        List<TraceExecutionDiagnostics.SeqGap> gaps = new ArrayList<>();
        Long previous = null;
        for (AgentTraceEvent event : events) {
            Long seq = event.getSeq();
            if (seq == null) {
                continue;
            }
            if (previous != null && seq != previous + 1) {
                gaps.add(new TraceExecutionDiagnostics.SeqGap(previous, seq));
            }
            previous = seq;
        }
        return gaps;
    }

    private int[] countMissing(NodeAcc node) {
        int[] counts = new int[2];
        if (node.kind != TraceExecutionNodeKind.RUN && !node.hasStart) {
            counts[0]++;
        }
        if (node.kind != TraceExecutionNodeKind.RUN && !node.hasEnd) {
            counts[1]++;
        }
        for (NodeAcc child : node.children) {
            int[] childCounts = countMissing(child);
            counts[0] += childCounts[0];
            counts[1] += childCounts[1];
        }
        return counts;
    }

    private static NodeAcc singleNode(String nodeId, TraceExecutionNodeKind kind,
                                      AgentTraceEvent event, Map<String, Object> attrs,
                                      TraceLinkConfidence confidence) {
        NodeAcc node = new NodeAcc(nodeId, kind, event.getTraceId(), event.getTurnNo());
        node.step = event.getStepNo();
        node.callId = event.getCallId();
        node.tool = event.getToolName();
        node.linkConfidence = confidence;
        node.add(event, true, true, attrs);
        return node;
    }

    private Map<String, Object> attributes(AgentTraceEvent event, Diag diag) {
        String payload = event.getPayloadJson();
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.isObject()) {
                diag.payloadError++;
                diag.warn("PAYLOAD_PARSE_ERROR");
                return Map.of();
            }
            if (root.path("truncated").asBoolean(false)
                    || root.path("serializationError").asBoolean(false)) {
                diag.payloadError++;
                diag.warn("PAYLOAD_PARSE_ERROR");
                return Map.of();
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            var fields = root.fields();
            while (fields.hasNext() && attributes.size() < MAX_ATTRIBUTES) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (!ATTRIBUTE_KEYS.contains(entry.getKey())) {
                    continue;
                }
                Object value = safeValue(entry.getValue(), 0);
                if (value != null) {
                    attributes.put(entry.getKey(), value);
                }
            }
            return attributes;
        } catch (Exception ex) {
            diag.payloadError++;
            diag.warn("PAYLOAD_PARSE_ERROR");
            return Map.of();
        }
    }

    private Object safeValue(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > MAX_DEPTH) {
            return null;
        }
        if (node.isNumber()) {
            // 不要用三元表达式：long/double 会数值提升成 double，导致 10 变成 10.0
            if (node.isIntegralNumber()) {
                return node.asLong();
            }
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual()) {
            String text = node.asText();
            return SAFE_STRING.matcher(text).matches() ? text : null;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonNode item : node) {
                if (values.size() >= MAX_LIST) {
                    break;
                }
                Object value = safeValue(item, depth + 1);
                if (value != null) {
                    values.add(value);
                }
            }
            return values.isEmpty() ? null : values;
        }
        if (node.isObject()) {
            Map<String, Object> values = new LinkedHashMap<>();
            var fields = node.fields();
            while (fields.hasNext() && values.size() < MAX_LIST) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (FORBIDDEN_NESTED_KEYS.contains(entry.getKey())) {
                    continue;
                }
                Object value = safeValue(entry.getValue(), depth + 1);
                if (value != null) {
                    values.put(entry.getKey(), value);
                }
            }
            return values.isEmpty() ? null : values;
        }
        return null;
    }

    private long number(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return fallback;
    }

    private static final class Diag {
        long droppedCount;
        int unknownType;
        int duplicateCallId;
        int orphanBusiness;
        int eventsAfterSessionEnd;
        int payloadError;
        final Set<String> warnings = new LinkedHashSet<>();

        void warn(String code) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(code);
            }
        }
    }

    private static final class RunAcc {
        final String traceId;
        final NodeAcc node;
        NodeAcc stream;
        final Map<Integer, TurnAcc> turns = new LinkedHashMap<>();
        final List<NodeAcc> direct = new ArrayList<>();
        final PlanAccumulator plan = new PlanAccumulator();
        StepAcc directStep;

        RunAcc(String traceId) {
            this.traceId = traceId;
            this.node = new NodeAcc("run:" + traceId, TraceExecutionNodeKind.RUN,
                    traceId, null);
            node.inferred = true;
            node.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
        }

        void handle(AgentTraceEvent event, Map<String, Object> attrs, Diag diag) {
            String type = event.getEventType();
            if (type.startsWith("stream/")) {
                stream = ensureStream();
                boolean start = "stream/open".equals(type);
                boolean end = "stream/close".equals(type) || "stream/error".equals(type);
                stream.add(event, start, end, attrs);
                return;
            }
            int turn = event.getTurnNo() == null ? 0 : event.getTurnNo();
            int step = event.getStepNo() == null ? 0 : event.getStepNo();
            if ("turn/start".equals(type) || "turn/end".equals(type)) {
                TurnAcc turnAcc = ensureTurn(turn);
                turnAcc.node.add(event, "turn/start".equals(type),
                        "turn/end".equals(type), attrs);
                return;
            }
            if ("step/start".equals(type) || "step/end".equals(type)) {
                TurnAcc turnAcc = ensureTurn(turn);
                StepAcc stepAcc = turnAcc.ensureStep(step);
                stepAcc.node.add(event, "step/start".equals(type),
                        "step/end".equals(type), attrs);
                return;
            }
            if (step > 0) {
                ensureTurn(turn).ensureStep(step).handle(event, attrs, diag);
                return;
            }
            if (type.startsWith("llm/") || type.startsWith("tool/")) {
                // 保存/修订 run 一般不产生 LLM/tool；仍按单步 0 容器配对，保留事实
                if (directStep == null) {
                    directStep = new StepAcc(traceId, turn, 0, node);
                }
                directStep.handle(event, attrs, diag);
                return;
            }
            if (type.startsWith("plan/") || "activity/created".equals(type)) {
                plan.handle(event, attrs, direct, diag);
                return;
            }
            diag.unknownType++;
            diag.warn("UNKNOWN_EVENT");
            direct.add(singleNode("unknown:" + event.getSeq(),
                    TraceExecutionNodeKind.UNKNOWN, event, attrs,
                    TraceLinkConfidence.NONE));
        }

        private NodeAcc ensureStream() {
            if (stream == null) {
                stream = new NodeAcc("stream:" + traceId,
                        TraceExecutionNodeKind.STREAM, traceId, null);
                stream.inferred = true;
                stream.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
            }
            return stream;
        }

        private TurnAcc ensureTurn(int turn) {
            return turns.computeIfAbsent(turn, key -> new TurnAcc(traceId, key));
        }

        NodeAcc finish(Diag diag) {
            if (stream != null) {
                stream.finish();
                node.children.add(stream);
            }
            List<TurnAcc> turnList = new ArrayList<>(turns.values());
            turnList.sort(Comparator.comparingInt(turn -> turn.turn));
            for (TurnAcc turn : turnList) {
                node.children.add(turn.finish(diag));
            }
            if (directStep != null) {
                directStep.finish();
                direct.addAll(directStep.node.children);
            }
            for (NodeAcc directNode : direct) {
                directNode.finish();
                node.children.add(directNode);
            }
            node.children.sort(Comparator.comparingLong(NodeAcc::orderSeq)
                    .thenComparing(child -> child.nodeId));
            node.finish();
            if (!node.children.isEmpty()) {
                node.hasStart = true;
                node.hasEnd = true;
                node.startSeq = node.children.get(0).orderSeq();
                node.endSeq = node.children.stream().mapToLong(NodeAcc::orderSeq).max().orElse(0L);
                node.startedAtMs = node.children.get(0).startedAtMs;
                node.endedAtMs = node.children.get(node.children.size() - 1).endedAtMs;
                if (node.startedAtMs != null && node.endedAtMs != null) {
                    node.durationMs = Math.max(0L, node.endedAtMs - node.startedAtMs);
                    node.durationDerived = true;
                }
                node.status = node.children.get(node.children.size() - 1).status;
            }
            return node;
        }
    }

    private static final class TurnAcc {
        final int turn;
        final NodeAcc node;
        final Map<Integer, StepAcc> steps = new LinkedHashMap<>();

        TurnAcc(String traceId, int turn) {
            this.turn = turn;
            this.node = new NodeAcc("turn:" + traceId + ":" + turn,
                    TraceExecutionNodeKind.TURN, traceId, turn);
            this.node.inferred = true;
            this.node.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
        }

        StepAcc ensureStep(int step) {
            return steps.computeIfAbsent(step,
                    key -> new StepAcc(node.traceId, turn, key, node));
        }

        NodeAcc finish(Diag diag) {
            List<StepAcc> stepList = new ArrayList<>(steps.values());
            stepList.sort(Comparator.comparingInt(step -> step.step));
            for (StepAcc step : stepList) {
                node.children.add(step.finish());
            }
            node.children.sort(Comparator.comparingLong(NodeAcc::orderSeq)
                    .thenComparing(child -> child.nodeId));
            node.finish();
            if (node.startSeq == null && !node.children.isEmpty()) {
                node.startSeq = node.children.get(0).orderSeq();
            }
            if (node.endSeq == null && !node.children.isEmpty()) {
                node.endSeq = node.children.get(node.children.size() - 1).orderSeq();
            }
            return node;
        }
    }

    private static final class StepAcc {
        final int turn;
        final int step;
        final NodeAcc node;
        final Deque<NodeAcc> llmOpen = new ArrayDeque<>();
        final Map<String, Deque<NodeAcc>> toolOpenByCall = new LinkedHashMap<>();
        final List<NodeAcc> toolOrder = new ArrayList<>();
        final PlanAccumulator plan = new PlanAccumulator();
        final Map<String, Integer> callIds = new LinkedHashMap<>();
        int llmOrdinal;
        int toolOrdinal;

        StepAcc(String traceId, int turn, int step, NodeAcc parent) {
            this.turn = turn;
            this.step = step;
            this.node = new NodeAcc("step:" + traceId + ":" + turn + ":" + step,
                    TraceExecutionNodeKind.STEP, traceId, turn);
            this.node.step = step;
            this.node.inferred = true;
            this.node.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
        }

        void handle(AgentTraceEvent event, Map<String, Object> attrs, Diag diag) {
            String type = event.getEventType();
            switch (type) {
                case "llm/request" -> handleLlm(event, attrs, true);
                case "llm/response", "llm/error" -> handleLlm(event, attrs, false);
                case "tool/call" -> handleTool(event, attrs, true, diag);
                case "tool/result", "tool/error" -> handleTool(event, attrs, false, diag);
                case "map/search", "place/semantic-retrieval", "route/estimate",
                        "schedule/free-window", "map/permission-filter" ->
                        handleBusiness(event, attrs, diag);
                case "plan/validate", "plan/draft", "plan/revise", "plan/confirm",
                        "activity/created", "plan/save-error" ->
                        plan.handle(event, attrs, node.children, diag);
                default -> {
                    diag.unknownType++;
                    diag.warn("UNKNOWN_EVENT");
                    node.children.add(singleNode("unknown:" + event.getSeq(),
                            TraceExecutionNodeKind.UNKNOWN, event, attrs,
                            TraceLinkConfidence.NONE));
                }
            }
        }

        private void handleLlm(AgentTraceEvent event, Map<String, Object> attrs, boolean start) {
            NodeAcc nodeRef;
            if (start) {
                llmOrdinal++;
                nodeRef = new NodeAcc("llm:" + event.getTraceId() + ":" + turn + ":" + step
                        + ":" + llmOrdinal, TraceExecutionNodeKind.LLM_CALL,
                        event.getTraceId(), turn);
                nodeRef.step = step;
                nodeRef.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
                llmOpen.add(nodeRef);
                node.children.add(nodeRef);
                nodeRef.add(event, true, false, attrs);
                return;
            }
            nodeRef = llmOpen.pollFirst();
            if (nodeRef == null) {
                nodeRef = new NodeAcc("llm:" + event.getTraceId() + ":" + turn + ":" + step
                        + ":orphan:" + event.getSeq(), TraceExecutionNodeKind.LLM_CALL,
                        event.getTraceId(), turn);
                nodeRef.step = step;
                nodeRef.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
                node.children.add(nodeRef);
                nodeRef.add(event, false, true, attrs);
                return;
            }
            nodeRef.add(event, false, true, attrs);
        }

        private void handleTool(AgentTraceEvent event, Map<String, Object> attrs,
                                boolean start, Diag diag) {
            if (start) {
                toolOrdinal++;
                String callId = event.getCallId();
                String key = callId == null || callId.isBlank()
                        ? "fallback:" + event.getToolName() + ":" + toolOrdinal
                        : callId;
                int seen = callIds.getOrDefault(key, 0) + 1;
                callIds.put(key, seen);
                if (seen > 1) {
                    diag.duplicateCallId++;
                    diag.warn("DUPLICATE_CALL_ID");
                }
                String id = callId == null || callId.isBlank()
                        ? "tool:" + event.getTraceId() + ":" + turn + ":" + step + ":"
                        + event.getToolName() + ":" + toolOrdinal
                        : "tool:" + event.getTraceId() + ":" + callId
                        + (seen > 1 ? "#" + seen : "");
                NodeAcc tool = new NodeAcc(id, TraceExecutionNodeKind.TOOL_CALL,
                        event.getTraceId(), turn);
                tool.step = step;
                tool.tool = event.getToolName();
                tool.callId = callId;
                tool.linkConfidence = callId == null || callId.isBlank()
                        ? TraceLinkConfidence.STRUCTURAL_STEP
                        : TraceLinkConfidence.EXPLICIT_CALL_ID;
                toolOpenByCall.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(tool);
                toolOrder.add(tool);
                node.children.add(tool);
                tool.add(event, true, false, attrs);
                return;
            }
            NodeAcc tool = findOpenTool(event);
            if (tool == null) {
                int seen = callIds.getOrDefault(event.getCallId(), 0);
                if (seen > 0) {
                    diag.duplicateCallId++;
                    diag.warn("DUPLICATE_CALL_ID");
                }
                tool = new NodeAcc("tool:" + event.getTraceId() + ":orphan:" + event.getSeq(),
                        TraceExecutionNodeKind.TOOL_CALL, event.getTraceId(), turn);
                tool.step = step;
                tool.tool = event.getToolName();
                tool.callId = event.getCallId();
                tool.linkConfidence = TraceLinkConfidence.STRUCTURAL_STEP;
                node.children.add(tool);
                tool.add(event, false, true, attrs);
                return;
            }
            tool.add(event, false, true, attrs);
        }

        private NodeAcc findOpenTool(AgentTraceEvent event) {
            String callId = event.getCallId();
            if (callId != null && !callId.isBlank()) {
                Deque<NodeAcc> queue = toolOpenByCall.get(callId);
                NodeAcc tool = queue == null ? null : queue.pollFirst();
                if (tool != null) {
                    return tool;
                }
            }
            for (int index = toolOrder.size() - 1; index >= 0; index--) {
                NodeAcc candidate = toolOrder.get(index);
                if (!candidate.hasEnd
                        && (event.getToolName() == null
                        || event.getToolName().equals(candidate.tool))) {
                    return candidate;
                }
            }
            for (int index = toolOrder.size() - 1; index >= 0; index--) {
                NodeAcc candidate = toolOrder.get(index);
                if (!candidate.hasEnd) {
                    return candidate;
                }
            }
            return null;
        }

        private void handleBusiness(AgentTraceEvent event, Map<String, Object> attrs, Diag diag) {
            TraceExecutionNodeKind kind = businessKind(event.getEventType());
            NodeAcc child = singleNode(businessId(event), kind, event, attrs,
                    event.getCallId() == null ? TraceLinkConfidence.STRUCTURAL_STEP
                            : TraceLinkConfidence.EXPLICIT_CALL_ID);
            for (int index = toolOrder.size() - 1; index >= 0; index--) {
                NodeAcc tool = toolOrder.get(index);
                boolean callMatch = event.getCallId() != null
                        && event.getCallId().equals(tool.callId);
                boolean nameMatch = event.getToolName() != null
                        && event.getToolName().equals(tool.tool);
                if (callMatch || nameMatch || (event.getCallId() == null
                        && event.getToolName() == null)) {
                    tool.children.add(child);
                    return;
                }
            }
            node.children.add(child);
            diag.orphanBusiness++;
            diag.warn("ORPHAN_BUSINESS_EVENT");
        }

        NodeAcc finish() {
            while (!llmOpen.isEmpty()) {
                NodeAcc open = llmOpen.pollFirst();
                open.warnings.add("MISSING_END");
            }
            for (Deque<NodeAcc> queue : toolOpenByCall.values()) {
                while (!queue.isEmpty()) {
                    queue.pollFirst().warnings.add("MISSING_END");
                }
            }
            for (NodeAcc tool : toolOrder) {
                tool.finish();
            }
            for (NodeAcc child : node.children) {
                child.finish();
            }
            node.children.sort(Comparator.comparingLong(NodeAcc::orderSeq)
                    .thenComparing(child -> child.nodeId));
            node.finish();
            if (node.startSeq == null && !node.children.isEmpty()) {
                node.startSeq = node.children.get(0).orderSeq();
            }
            if (node.endSeq == null && !node.children.isEmpty()) {
                node.endSeq = node.children.get(node.children.size() - 1).orderSeq();
            }
            return node;
        }

        private String businessId(AgentTraceEvent event) {
            return event.getEventType().replace('/', '-') + ":" + event.getTraceId()
                    + ":" + event.getSeq();
        }

        private TraceExecutionNodeKind businessKind(String type) {
            return switch (type) {
                case "map/search" -> TraceExecutionNodeKind.MAP_SEARCH;
                case "place/semantic-retrieval" -> TraceExecutionNodeKind.PLACE_RETRIEVAL;
                case "route/estimate" -> TraceExecutionNodeKind.ROUTE_ESTIMATE;
                case "schedule/free-window" -> TraceExecutionNodeKind.SCHEDULE_WINDOW;
                case "map/permission-filter" -> TraceExecutionNodeKind.PERMISSION_FILTER;
                default -> TraceExecutionNodeKind.UNKNOWN;
            };
        }
    }

    private static final class PlanAccumulator {
        final Deque<NodeAcc> openConfirms = new ArrayDeque<>();
        NodeAcc lastConfirm;

        void handle(AgentTraceEvent event, Map<String, Object> attrs,
                    List<NodeAcc> parent, Diag diag) {
            String type = event.getEventType();
            if ("plan/confirm".equals(type)) {
                String status = event.getStatus();
                String key = attrs.getOrDefault("draftVersion", "?")
                        + "|" + attrs.getOrDefault("operation", "?");
                if ("RUNNING".equals(status)) {
                    NodeAcc confirm = new NodeAcc(
                            "plan-confirm:" + event.getTraceId() + ":" + event.getSeq(),
                            TraceExecutionNodeKind.PLAN_CONFIRM, event.getTraceId(),
                            event.getTurnNo());
                    confirm.step = event.getStepNo();
                    confirm.linkConfidence = TraceLinkConfidence.REFERENCE_VERSION;
                    confirm.add(event, true, false, attrs);
                    openConfirms.add(confirm);
                    parent.add(confirm);
                    lastConfirm = confirm;
                    return;
                }
                NodeAcc confirm = null;
                Iterator<NodeAcc> iterator = openConfirms.iterator();
                while (iterator.hasNext()) {
                    NodeAcc candidate = iterator.next();
                    if (candidate.attributes.getOrDefault("draftVersion", "?")
                            .equals(attrs.getOrDefault("draftVersion", "?"))
                            && candidate.attributes.getOrDefault("operation", "?")
                            .equals(attrs.getOrDefault("operation", "?"))) {
                        confirm = candidate;
                        iterator.remove();
                        break;
                    }
                }
                if (confirm == null) {
                    confirm = new NodeAcc(
                            "plan-confirm:" + event.getTraceId() + ":" + event.getSeq(),
                            TraceExecutionNodeKind.PLAN_CONFIRM, event.getTraceId(),
                            event.getTurnNo());
                    confirm.step = event.getStepNo();
                    confirm.linkConfidence = TraceLinkConfidence.REFERENCE_VERSION;
                    parent.add(confirm);
                }
                confirm.add(event, false, true, attrs);
                lastConfirm = confirm;
                return;
            }
            NodeAcc node = new NodeAcc(
                    planId(event), planKind(type), event.getTraceId(), event.getTurnNo());
            node.step = event.getStepNo();
            node.linkConfidence = TraceLinkConfidence.REFERENCE_VERSION;
            node.add(event, true, true, attrs);
            boolean sameDraftVersion = lastConfirm != null
                    && Objects.equals(lastConfirm.attributes.get("draftVersion"),
                    attrs.get("draftVersion"));
            if (("activity/created".equals(type) || "plan/save-error".equals(type))
                    && sameDraftVersion) {
                lastConfirm.children.add(node);
            } else {
                parent.add(node);
            }
        }

        private String planId(AgentTraceEvent event) {
            return switch (event.getEventType()) {
                case "plan/validate" -> "plan-validate:" + event.getTraceId() + ":" + event.getSeq();
                case "plan/draft" -> "plan-draft:" + event.getTraceId() + ":" + event.getSeq();
                case "plan/revise" -> "plan-revise:" + event.getTraceId() + ":" + event.getSeq();
                case "activity/created" -> "activity:" + event.getTraceId() + ":" + event.getSeq();
                case "plan/save-error" -> "plan-save-error:" + event.getTraceId() + ":" + event.getSeq();
                default -> "plan:" + event.getTraceId() + ":" + event.getSeq();
            };
        }

        private TraceExecutionNodeKind planKind(String type) {
            return switch (type) {
                case "plan/validate" -> TraceExecutionNodeKind.PLAN_VALIDATE;
                case "plan/draft" -> TraceExecutionNodeKind.PLAN_DRAFT;
                case "plan/revise" -> TraceExecutionNodeKind.PLAN_REVISE;
                case "plan/confirm" -> TraceExecutionNodeKind.PLAN_CONFIRM;
                case "activity/created" -> TraceExecutionNodeKind.ACTIVITY_CREATED;
                case "plan/save-error" -> TraceExecutionNodeKind.PLAN_SAVE_ERROR;
                default -> TraceExecutionNodeKind.UNKNOWN;
            };
        }
    }

    private static final class NodeAcc {
        final String nodeId;
        final TraceExecutionNodeKind kind;
        String traceId;
        Integer turn;
        Integer step;
        String callId;
        String tool;
        String eventType;
        String status;
        Long startSeq;
        Long endSeq;
        Long startedAtMs;
        Long endedAtMs;
        Long durationMs;
        boolean durationDerived;
        boolean hasStart;
        boolean hasEnd;
        boolean inferred;
        String errorCode;
        TraceLinkConfidence linkConfidence = TraceLinkConfidence.NONE;
        final Map<String, Object> attributes = new LinkedHashMap<>();
        final List<Long> eventSeqs = new ArrayList<>();
        final List<NodeAcc> children = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        NodeAcc(String nodeId, TraceExecutionNodeKind kind, String traceId, Integer turn) {
            this.nodeId = nodeId;
            this.kind = kind;
            this.traceId = traceId;
            this.turn = turn;
        }

        void add(AgentTraceEvent event, boolean start, boolean end,
                 Map<String, Object> attrs) {
            if (eventType == null) {
                eventType = event.getEventType();
            }
            if (event.getSeq() != null) {
                eventSeqs.add(event.getSeq());
            }
            if (event.getStatus() != null) {
                status = event.getStatus();
            }
            if (event.getErrorCode() != null) {
                errorCode = event.getErrorCode();
            }
            if (event.getToolName() != null) {
                tool = event.getToolName();
            }
            if (event.getCallId() != null) {
                callId = event.getCallId();
            }
            if (start) {
                hasStart = true;
                if (startSeq == null || (event.getSeq() != null && event.getSeq() < startSeq)) {
                    startSeq = event.getSeq();
                }
                if (startedAtMs == null) {
                    startedAtMs = event.getOccurredAtMs();
                }
            }
            if (end) {
                hasEnd = true;
                if (endSeq == null || (event.getSeq() != null && event.getSeq() > endSeq)) {
                    endSeq = event.getSeq();
                }
                endedAtMs = event.getOccurredAtMs();
                if (event.getDurationMs() != null) {
                    durationMs = event.getDurationMs();
                }
            }
            if (!start && !end) {
                hasStart = true;
                hasEnd = true;
                startSeq = event.getSeq();
                endSeq = event.getSeq();
                startedAtMs = event.getOccurredAtMs();
                endedAtMs = event.getOccurredAtMs();
                durationMs = event.getDurationMs();
            }
            if (attrs != null) {
                attrs.forEach(attributes::putIfAbsent);
            }
        }

        void finish() {
            if (hasStart && hasEnd && durationMs == null
                    && startedAtMs != null && endedAtMs != null) {
                durationMs = Math.max(0L, endedAtMs - startedAtMs);
                durationDerived = true;
            }
        }

        long orderSeq() {
            if (startSeq != null) {
                return startSeq;
            }
            long min = Long.MAX_VALUE;
            for (Long seq : eventSeqs) {
                if (seq != null && seq < min) {
                    min = seq;
                }
            }
            return min == Long.MAX_VALUE ? Long.MAX_VALUE : min;
        }

        TraceExecutionNode toNode() {
            List<TraceExecutionNode> childNodes = children.stream()
                    .sorted(Comparator.comparingLong(NodeAcc::orderSeq)
                            .thenComparing(child -> child.nodeId))
                    .map(NodeAcc::toNode)
                    .toList();
            return new TraceExecutionNode(nodeId, kind, eventType, traceId, turn, step,
                    callId, tool, status, startSeq, endSeq, eventSeqs, startedAtMs,
                    endedAtMs, durationMs, durationDerived, hasStart, hasEnd, inferred,
                    errorCode, linkConfidence, attributes, childNodes, warnings);
        }
    }
}
