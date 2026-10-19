package com.campus.ai.trace.mapper;

import com.campus.ai.trace.domain.AgentTraceEvent;
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

    /** JSONL 导出：keyset 分页，按 seq ASC，避免大 session 一次装入内存 */
    List<AgentTraceEvent> selectExportBatch(@Param("sessionId") String sessionId,
                                            @Param("afterSeq") long afterSeq,
                                            @Param("size") int size);

    Integer selectMaxTurn(@Param("sessionId") String sessionId);

    /** 详情耗时统计：只取类型/工具/耗时三列，duration_ms 非空 */
    List<AgentTraceEvent> selectDurations(@Param("sessionId") String sessionId);

    /** 详情草稿版本聚合：plan/draft + plan/validate + plan/revise，按 seq ASC */
    List<AgentTraceEvent> selectPlanEvents(@Param("sessionId") String sessionId);
}
