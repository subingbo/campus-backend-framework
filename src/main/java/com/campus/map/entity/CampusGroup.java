package com.campus.map.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CampusGroup {

    private Long id;
    private String name;
    private String description;
    private Long ownerId;
    private String inviteCode;
    private String visibility;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
