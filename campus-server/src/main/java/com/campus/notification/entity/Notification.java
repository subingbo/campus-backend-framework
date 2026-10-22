package com.campus.notification.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Notification {

    private Long id;
    private Long userId;
    private String type;
    private String title;
    private String content;
    private String targetType;
    private Long targetId;
    private String payloadJson;
    private LocalDateTime readAt;
    private String dedupeKey;
    private LocalDateTime createdAt;
}
