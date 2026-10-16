package com.campus.map.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MapMember {

    private Long id;
    private Long mapId;
    private Long userId;
    private String role;
    private LocalDateTime createdAt;
}
