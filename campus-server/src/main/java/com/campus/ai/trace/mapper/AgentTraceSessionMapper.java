package com.campus.ai.trace.mapper;

import com.campus.ai.trace.domain.AgentTraceSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AgentTraceSessionMapper {

    int insert(AgentTraceSession session);

    AgentTraceSession selectById(@Param("id") String id);

    /** 权限：只返回属于 requesterUserId 的会话；非本人与不存在统一返回 null */
    AgentTraceSession selectByIdAndUser(@Param("id") String id, @Param("userId") Long userId);

    AgentTraceSession selectLatestByConversation(@Param("conversationId") Long conversationId);

    List<AgentTraceSession> selectPage(@Param("userId") Long userId,
                                       @Param("status") String status,
                                       @Param("offset") int offset,
                                       @Param("size") int size);

    long countPage(@Param("userId") Long userId, @Param("status") String status);

    /** 行锁分配 seq：必须在事务内调用 */
    Long selectNextSeqForUpdate(@Param("id") String id);

    int allocateSeq(@Param("id") String id, @Param("newSeq") long newSeq);

    int incrementToolCall(@Param("id") String id);

    int incrementError(@Param("id") String id);

    int addTokens(@Param("id") String id,
                  @Param("prompt") Long prompt,
                  @Param("completion") Long completion,
                  @Param("total") Long total);

    int bindDraft(@Param("id") String id, @Param("draftId") Long draftId);

    /** 只在字段为空时绑定，避免后续保存覆盖第一次已知的群/地图上下文 */
    int bindContext(@Param("id") String id,
                    @Param("sharedMapId") Long sharedMapId,
                    @Param("groupId") Long groupId);

    /** 只在 RUNNING 时结束，保证并发 finish/cleanup 只有一个生效 */
    int finish(@Param("id") String id,
               @Param("status") String status,
               @Param("endedAt") LocalDateTime endedAt,
               @Param("durationMs") Long durationMs,
               @Param("lastErrorCode") String lastErrorCode);

    int incrementDropped(@Param("id") String id, @Param("count") long count);

    List<String> selectStaleRunning(@Param("cutoff") LocalDateTime cutoff,
                                    @Param("limit") int limit);

    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff,
                        @Param("limit") int limit);
}
