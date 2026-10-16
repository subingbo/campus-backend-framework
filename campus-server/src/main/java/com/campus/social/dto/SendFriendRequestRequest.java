package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SendFriendRequestRequest {

    @NotNull(message = "对方用户不能为空")
    private Long userId;

    @Size(max = 128, message = "验证留言最长 128 字")
    private String message;
}
