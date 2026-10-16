package com.campus.social.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Conversation {

    private Long id;
    private String type;
    private Long mapId;
    private Long placeId;
    private LocalDateTime createdAt;
}
