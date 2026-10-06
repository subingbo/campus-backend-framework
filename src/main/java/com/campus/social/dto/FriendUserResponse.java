package com.campus.social.dto;

import lombok.Data;

@Data
public class FriendUserResponse {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private boolean online;
}
