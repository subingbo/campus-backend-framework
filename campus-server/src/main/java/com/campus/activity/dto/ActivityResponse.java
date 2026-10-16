package com.campus.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityResponse {

    private Long id;
    private Long placeId;
    private Long creatorId;
    private String creatorNickname;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer maxMembers;
    private long joinedCount;
    private boolean joined;
    private String phase;

    @JsonIgnore
    private Integer status;
}
