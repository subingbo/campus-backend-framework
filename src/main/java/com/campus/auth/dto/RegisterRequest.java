package com.campus.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "注册请求")
public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 32)
    private String username;

    @NotBlank
    @Size(min = 6, max = 32)
    private String password;

    private String nickname;
}
