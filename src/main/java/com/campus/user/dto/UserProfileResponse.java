package com.campus.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "当前用户信息")
public class UserProfileResponse {

    private Long id;
    private String username;
    private String nickname;
    private List<String> roles;
    private List<String> permissions;
}
