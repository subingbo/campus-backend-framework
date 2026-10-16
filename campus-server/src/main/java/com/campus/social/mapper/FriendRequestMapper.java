package com.campus.social.mapper;

import com.campus.social.dto.FriendRequestResponse;
import com.campus.social.dto.SearchUserResponse;
import com.campus.social.entity.FriendRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FriendRequestMapper {

    int insert(FriendRequest request);

    FriendRequest selectById(@Param("id") Long id);

    FriendRequest selectPendingBetween(@Param("fromUserId") Long fromUserId, @Param("toUserId") Long toUserId);

    List<FriendRequestResponse> selectReceived(@Param("userId") Long userId);

    List<FriendRequestResponse> selectSent(@Param("userId") Long userId);

    int countReceivedPending(@Param("userId") Long userId);

    int updateStatus(@Param("id") Long id, @Param("status") int status);

    List<SearchUserResponse> searchUsers(@Param("userId") Long userId, @Param("keyword") String keyword);
}
