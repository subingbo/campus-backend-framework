package com.campus.social.mapper;

import com.campus.social.dto.FriendUserResponse;
import com.campus.social.entity.Friend;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FriendMapper {

    int insert(Friend friend);

    int deleteBetween(@Param("userIdA") Long userIdA, @Param("userIdB") Long userIdB);

    int existsBetween(@Param("userIdA") Long userIdA, @Param("userIdB") Long userIdB);

    List<FriendUserResponse> selectFriends(@Param("userId") Long userId);

    List<Long> selectFriendIds(@Param("userId") Long userId);
}
