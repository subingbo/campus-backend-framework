package com.campus.post.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostListItemResponse {

    private Long id;
    private Long userId;
    private String authorNickname;
    private String authorAvatar;
    private String content;
    private long likeCount;
    private long commentCount;
    private boolean liked;
    private LocalDateTime createdAt;
}
