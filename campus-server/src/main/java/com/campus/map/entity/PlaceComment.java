package com.campus.map.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PlaceComment {

    private Long id;
    private Long mapId;
    private Long placeId;
    private Long userId;
    private String content;
    private LocalDateTime createdAt;
}
