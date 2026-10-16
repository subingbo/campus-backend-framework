package com.campus.post.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class CommentResponse {

    private Long id;
    private Long userId;
    private String authorNickname;
    private String authorAvatar;
    private String content;
    private LocalDateTime createdAt;
    private Long parentId;
    private List<CommentResponse> replies = new ArrayList<>();
}
