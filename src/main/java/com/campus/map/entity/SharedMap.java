package com.campus.map.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SharedMap {

    private Long id;
    private Long groupId;
    private String name;
    private String description;
    private String coverImage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
