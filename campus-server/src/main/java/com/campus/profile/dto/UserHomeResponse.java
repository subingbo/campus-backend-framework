package com.campus.profile.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "用户主页")
public class UserHomeResponse {

    private Long id;
    private String nickname;
    private String avatar;
    private LocalDateTime createdAt;
    private long postCount;
    private long likeReceivedCount;
}
