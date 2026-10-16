package com.campus.ai.mapper;

import com.campus.ai.entity.AiConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiConversationMapper {

    int insert(AiConversation conversation);

    AiConversation selectById(@Param("id") Long id);

    /** save 幂等用：行锁串行化同一会话的并发保存 */
    AiConversation selectByIdForUpdate(@Param("id") Long id);

    List<AiConversation> selectByUser(@Param("userId") Long userId);

    /** 新草稿产生：plan_version+1；若原为 SAVED 则回到 DRAFT 并清空旧活动关联 */
    int updatePlanResult(@Param("id") Long id,
                         @Param("planJson") String planJson,
                         @Param("planVersion") int planVersion,
                         @Param("status") String status);

    /** 草稿保存为活动（幂等：status 已是 SAVED 时影响行数为 0） */
    int markSaved(@Param("id") Long id, @Param("activityId") Long activityId);

    int updateTitle(@Param("id") Long id, @Param("title") String title);
}
