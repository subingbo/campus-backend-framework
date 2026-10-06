package com.campus.social.service;

import com.campus.social.dto.FriendRequestListResponse;
import com.campus.social.dto.FriendRequestResponse;
import com.campus.social.dto.FriendUserResponse;
import com.campus.social.dto.SearchUserResponse;
import com.campus.social.dto.SendFriendRequestRequest;

import java.util.List;

public interface FriendService {

    List<SearchUserResponse> searchUsers(String keyword);

    FriendRequestResponse sendRequest(SendFriendRequestRequest request);

    FriendRequestListResponse listRequests();

    void accept(Long requestId);

    void reject(Long requestId);

    List<FriendUserResponse> listFriends();

    void removeFriend(Long friendUserId);
}
