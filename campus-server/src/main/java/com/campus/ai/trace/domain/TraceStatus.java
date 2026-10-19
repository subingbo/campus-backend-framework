package com.campus.ai.trace.domain;

/** Trace 状态常量：会话状态与事件状态分开使用 */
public final class TraceStatus {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String INTERRUPTED = "INTERRUPTED";
    public static final String FAILED = "FAILED";

    public static final String SUCCESS = "SUCCESS";
    public static final String CANCELLED = "CANCELLED";
    public static final String PARTIAL = "PARTIAL";
    public static final String SKIPPED = "SKIPPED";

    private TraceStatus() {
    }

    public static boolean isTerminalSessionStatus(String status) {
        return COMPLETED.equals(status) || INTERRUPTED.equals(status) || FAILED.equals(status);
    }

    public static boolean isSessionStatus(String status) {
        return RUNNING.equals(status) || isTerminalSessionStatus(status);
    }
}
