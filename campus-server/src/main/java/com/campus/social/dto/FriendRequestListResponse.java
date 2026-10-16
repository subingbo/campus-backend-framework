package com.campus.social.dto;

import lombok.Data;

import java.util.List;

@Data
public class FriendRequestListResponse {

    private List<FriendRequestResponse> received;
    private List<FriendRequestResponse> sent;
}
