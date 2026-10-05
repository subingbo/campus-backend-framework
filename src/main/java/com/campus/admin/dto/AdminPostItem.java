package com.campus.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminPostItem {

    private Long id;
    private Long userId;
    private Long placeId;
    private String content;
    private Integer status;
    private LocalDateTime createdAt;
}
