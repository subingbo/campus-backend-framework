package com.campus.social.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePrivateConversationRequest {

    @NotNull(message = "对方用户不能为空")
    private Long userId;
}
