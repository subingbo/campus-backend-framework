package com.campus.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminUserItem {

    private Long id;
    private String username;
    private String nickname;
    private String avatar;
    private Integer status;
    private LocalDateTime createdAt;
}
