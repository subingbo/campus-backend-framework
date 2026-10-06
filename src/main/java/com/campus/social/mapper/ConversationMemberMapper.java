package com.campus.social.mapper;

import com.campus.social.entity.ConversationMember;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ConversationMemberMapper {

    int insertIgnore(ConversationMember member);

    List<Long> selectUserIdsByConversation(@Param("conversationId") Long conversationId);

    List<Long> selectConversationIdsByUser(@Param("userId") Long userId);

    int exists(@Param("conversationId") Long conversationId, @Param("userId") Long userId);
}
