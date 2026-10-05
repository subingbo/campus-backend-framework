package com.campus.activity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateActivityRequest {

    @NotNull
    private Long placeId;

    @NotBlank
    @Size(max = 128)
    private String title;

    @Size(max = 1000)
    private String description;

    @NotNull
    private LocalDateTime startTime;

    @NotNull
    private LocalDateTime endTime;

    @NotNull
    @Min(1)
    @Max(500)
    private Integer maxMembers;
}
