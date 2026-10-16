package com.campus.social.mapper;

import com.campus.social.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationMapper {

    int insert(Conversation conversation);

    Conversation selectById(@Param("id") Long id);

    Conversation selectPrivateBetween(@Param("userIdA") Long userIdA, @Param("userIdB") Long userIdB);

    Conversation selectByMapId(@Param("mapId") Long mapId);

    Conversation selectByPlaceId(@Param("placeId") Long placeId);
}
