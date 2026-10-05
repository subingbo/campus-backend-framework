package com.campus.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "Token 响应")
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private long expiresIn;
}
