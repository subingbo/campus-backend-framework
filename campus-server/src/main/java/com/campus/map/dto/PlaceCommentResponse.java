package com.campus.map.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PlaceCommentResponse {

    private Long id;
    private Long userId;
    private String nickname;
    private String avatar;
    private String content;
    private LocalDateTime createdAt;
}
