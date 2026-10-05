package com.campus.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateUserStatusRequest {

    @NotNull
    @Min(0)
    @Max(1)
    private Integer status;
}
