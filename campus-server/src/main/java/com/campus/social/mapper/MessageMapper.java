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

    ChatMessageResponse selectById(@Param("id") Long id);

    /** 幂等查询：同一发送者在同一会话内的 clientMsgId 只对应一条消息 */
    ChatMessageResponse selectByClientMsgId(@Param("conversationId") Long conversationId,
                                           @Param("senderId") Long senderId,
                                           @Param("clientMsgId") String clientMsgId);

    /** 群聊上一轮 @AI 的 AGENT 回复（不含普通文本消息），无则 null */
    com.campus.social.entity.Message selectAgentReply(@Param("conversationId") Long conversationId);
}
