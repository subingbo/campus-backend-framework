package com.campus.ai.mapper;

import com.campus.ai.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AiMessageMapper {

    int insert(AiMessage message);

    /** 最近 N 条（新→旧），调用方自行反转成时间正序 */
    List<AiMessage> selectRecent(@Param("conversationId") Long conversationId,
                                 @Param("limit") int limit);
}
