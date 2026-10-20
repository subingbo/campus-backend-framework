package com.campus.ai.trace.execution;

import java.util.List;

/** 一次 Trace 会话的执行链根节点与诊断。 */
public record TraceExecutionAssembly(
        String sessionId,
        String sessionStatus,
        List<TraceExecutionNode> roots,
        TraceExecutionDiagnostics diagnostics
) {

    public TraceExecutionAssembly {
        roots = roots == null ? List.of() : List.copyOf(roots);
    }
}
