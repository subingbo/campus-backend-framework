package com.campus.post.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostComment {

    private Long id;
    private Long postId;
    private Long userId;
    private Long parentId;
    private String content;
    private LocalDateTime createdAt;
}
