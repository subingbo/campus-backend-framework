package com.campus.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "刷新 Token 请求")
public class RefreshRequest {

    @NotBlank
    private String refreshToken;
}
