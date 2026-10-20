package com.campus.ai.trace.execution;

/** 节点与父节点/关联事件的证据强度；用于区分确定关联与结构性推断。 */
public enum TraceLinkConfidence {
    SESSION,
    EXPLICIT_CALL_ID,
    STRUCTURAL_STEP,
    REFERENCE_VERSION,
    NONE
}
