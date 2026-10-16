package com.campus.activity.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Activity {

    private Long id;
    private Long placeId;
    private Long creatorId;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer maxMembers;
    private String category;
    private String checkinCode;
    private LocalDateTime checkinOpenUntil;
    private Integer status;
    private LocalDateTime createdAt;
}
