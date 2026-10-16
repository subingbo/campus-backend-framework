package com.campus.place.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "创建地点")
public class CreatePlaceRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    @NotNull
    private Long categoryId;

    @NotNull
    @DecimalMin(value = "-180")
    @DecimalMax(value = "180")
    private BigDecimal longitude;

    @NotNull
    @DecimalMin(value = "-90")
    @DecimalMax(value = "90")
    private BigDecimal latitude;

    @Size(max = 256)
    private String address;

    @Schema(description = "为 true 时忽略 5 米内候选并强制新建")
    private Boolean confirmNew = false;
}
