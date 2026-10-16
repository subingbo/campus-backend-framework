package com.campus.report.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class HandleReportRequest {

    @NotBlank
    private String decision;

    private String action;
}
