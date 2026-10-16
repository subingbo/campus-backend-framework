package com.campus.map.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MapPlace {

    private Long id;
    private Long mapId;
    private Long placeId;
    private Long createdBy;
    private LocalDateTime createdAt;
}
