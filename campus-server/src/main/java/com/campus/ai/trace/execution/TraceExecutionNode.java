package com.campus.ai.trace.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 只读执行链节点。attributes 只来自显式白名单；缺失开始/结束保留空值并标记，不补造状态。
 */
public record TraceExecutionNode(
        String nodeId,
        TraceExecutionNodeKind kind,
        String eventType,
        String traceId,
        Integer turn,
        Integer step,
        String callId,
        String tool,
        String status,
        Long startSeq,
        Long endSeq,
        List<Long> eventSeqs,
        Long startedAtMs,
        Long endedAtMs,
        Long durationMs,
        boolean durationDerived,
        boolean hasStart,
        boolean hasEnd,
        boolean inferred,
        String errorCode,
        TraceLinkConfidence linkConfidence,
        Map<String, Object> attributes,
        List<TraceExecutionNode> children,
        List<String> warnings
) {

    public TraceExecutionNode {
        eventSeqs = eventSeqs == null ? List.of() : List.copyOf(eventSeqs);
        attributes = attributes == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        children = children == null ? List.of() : List.copyOf(children);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
