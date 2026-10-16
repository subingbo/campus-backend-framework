package com.campus.social.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Friend {

    private Long id;
    private Long userId;
    private Long friendId;
    private LocalDateTime createdAt;
}
