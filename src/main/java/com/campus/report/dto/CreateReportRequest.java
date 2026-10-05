package com.campus.report.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateReportRequest {

    @NotBlank
    private String targetType;

    @NotNull
    private Long targetId;

    @NotBlank
    @Size(max = 256)
    private String reason;
}
