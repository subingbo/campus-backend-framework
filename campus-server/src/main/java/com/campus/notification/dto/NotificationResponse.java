package com.campus.notification.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class NotificationResponse {

    private Long id;
    private String type;
    private String title;
    private String content;
    private String targetType;
    private Long targetId;
    private Map<String, Object> payload;
    private boolean read;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
