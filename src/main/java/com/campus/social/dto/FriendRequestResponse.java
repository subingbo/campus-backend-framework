package com.campus.social.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FriendRequestResponse {

    private Long id;
    private Long fromUserId;
    private String fromUsername;
    private String fromNickname;
    private Long toUserId;
    private String toUsername;
    private String toNickname;
    private String message;
    private Integer status;
    private LocalDateTime createdAt;
}
