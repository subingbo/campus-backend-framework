package com.campus.ai.trace.execution;

import java.util.List;

/** 组装诊断：只报告事实和缺口，不推断丢失事件的内容。 */
public record TraceExecutionDiagnostics(
        int eventCount,
        Long firstSeq,
        Long lastSeq,
        boolean seqContiguous,
        List<SeqGap> seqGaps,
        long droppedCount,
        int unknownTypeCount,
        int missingStartCount,
        int missingEndCount,
        int duplicateCallIdCount,
        int orphanBusinessCount,
        int eventsAfterSessionEnd,
        int payloadErrorCount,
        List<String> warnings
) {

    public TraceExecutionDiagnostics {
        seqGaps = seqGaps == null ? List.of() : List.copyOf(seqGaps);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public record SeqGap(long fromSeq, long toSeq) {
    }
}
