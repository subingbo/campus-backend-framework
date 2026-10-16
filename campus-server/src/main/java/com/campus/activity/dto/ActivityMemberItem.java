package com.campus.activity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityMemberItem {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private LocalDateTime joinedAt;
    private boolean checkedIn;
    private LocalDateTime checkedInAt;
}
