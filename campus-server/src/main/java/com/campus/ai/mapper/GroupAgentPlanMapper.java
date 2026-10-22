package com.campus.ai.mapper;

import com.campus.ai.entity.GroupAgentPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface GroupAgentPlanMapper {

    int insert(GroupAgentPlan plan);

    GroupAgentPlan selectById(@Param("id") Long id);

    GroupAgentPlan selectByMessageId(@Param("messageId") Long messageId);

    List<GroupAgentPlan> selectByConversation(@Param("conversationId") Long conversationId,
                                              @Param("limit") int limit);
}
