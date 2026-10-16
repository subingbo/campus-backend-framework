package com.campus.map.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MapMemberResponse {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private String role;
    private LocalDateTime joinedAt;
}
