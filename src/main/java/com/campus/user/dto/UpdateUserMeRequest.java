package com.campus.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "修改个人资料")
public class UpdateUserMeRequest {

    @Size(max = 64, message = "昵称最长 64 字符")
    private String nickname;

    @Size(max = 512, message = "头像 URL 最长 512 字符")
    private String avatar;
}
