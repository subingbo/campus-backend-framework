package com.campus.post.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Post {

    private Long id;
    private Long userId;
    private Long placeId;
    private String content;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
