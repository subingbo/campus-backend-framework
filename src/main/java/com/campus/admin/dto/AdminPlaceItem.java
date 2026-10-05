package com.campus.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminPlaceItem {

    private Long id;
    private String name;
    private Long creatorId;
    private Integer status;
    private LocalDateTime createdAt;
}
