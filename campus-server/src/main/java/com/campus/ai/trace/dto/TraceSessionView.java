package com.campus.ai.trace.dto;

import com.campus.ai.trace.domain.TraceStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 追踪会话列表/详情返回；complete 由服务端按真实状态与丢失计数判定 */
@Data
public class TraceSessionView {

    private String sessionId;
    private Long conversationId;
    private Long userId;
    private Long sharedMapId;
    private Long groupId;
    private Long draftId;
    private String triggerSource;
    private String provider;
    private String model;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Long durationMs;
    private Long promptTokens;
    private Long completionTokens;
    private Long totalTokens;
    private Integer eventCount;
    private Integer toolCallCount;
    private Integer errorCount;
    private Integer droppedCount;
    private String lastErrorCode;
    /** 只有 COMPLETED 且 dropped_count == 0 才是完整追踪 */
    private boolean complete;

    /** P1 增强：仅详情返回，耗时统计（类型/工具聚合的 p50/p95） */
    private List<TraceDurationStat> stats;
    /** P1 增强：仅详情返回，草稿版本摘要（供前端做版本 diff） */
    private List<TraceDraftVersionView> draftVersions;

    public static boolean isComplete(String status, Integer droppedCount) {
        return TraceStatus.COMPLETED.equals(status)
                && (droppedCount == null || droppedCount == 0);
    }
}
