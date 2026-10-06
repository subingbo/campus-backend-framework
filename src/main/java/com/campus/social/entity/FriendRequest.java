package com.campus.social.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class FriendRequest {

    private Long id;
    private Long fromUserId;
    private Long toUserId;
    private Integer status;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}
