package com.campus.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "邀请码加入群组")
public class JoinGroupRequest {

    @NotBlank
    @Size(max = 16)
    private String inviteCode;
}
