package com.campus.ai.trace.mapper;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.dto.TraceLlmUsageStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentTraceEventMapper {

    int insert(AgentTraceEvent event);

    List<AgentTraceEvent> selectPage(@Param("sessionId") String sessionId,
                                     @Param("type") String type,
                                     @Param("callId") String callId,
                                     @Param("step") Integer step,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    long countPage(@Param("sessionId") String sessionId,
                   @Param("type") String type,
                   @Param("callId") String callId,
                   @Param("step") Integer step);

    /** 自动刷新增量：只取 seq > afterSeq 的事件，仍应用 type/callId/step 过滤 */
    List<AgentTraceEvent> selectAfterSeq(@Param("sessionId") String sessionId,
                                         @Param("type") String type,
                                         @Param("callId") String callId,
                                         @Param("step") Integer step,
                                         @Param("afterSeq") long afterSeq,
                                         @Param("size") int size);

    /** JSONL 导出：keyset 分页，按 seq ASC，避免大 session 一次装入内存 */
    List<AgentTraceEvent> selectExportBatch(@Param("sessionId") String sessionId,
                                            @Param("afterSeq") long afterSeq,
                                            @Param("size") int size);

    /** 成本估算前置校验：批量统计各 session 的 llm/response usage/model 完整性 */
    List<TraceLlmUsageStat> selectLlmUsageStats(@Param("sessionIds") List<String> sessionIds);

    Integer selectMaxTurn(@Param("sessionId") String sessionId);

    /** 详情耗时统计：只取类型/工具/耗时三列，duration_ms 非空 */
    List<AgentTraceEvent> selectDurations(@Param("sessionId") String sessionId);

    /** 详情草稿版本聚合：plan/draft + plan/validate + plan/revise，按 seq ASC */
    List<AgentTraceEvent> selectPlanEvents(@Param("sessionId") String sessionId);
}
