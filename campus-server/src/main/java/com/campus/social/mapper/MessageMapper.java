package com.campus.social.mapper;

import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface MessageMapper {

    int insert(Message message);

    List<ChatMessageResponse> selectByConversation(@Param("conversationId") Long conversationId,
                                                   @Param("beforeId") Long beforeId,
                                                   @Param("limit") int limit);

    ChatMessageResponse selectLastByConversation(@Param("conversationId") Long conversationId);
}
