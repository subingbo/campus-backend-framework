package com.campus.map.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "向共享地图添加地点")
public class AddMapPlaceRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    @NotNull(message = "请选择分类")
    private Long categoryId;

    @NotNull(message = "经度不能为空")
    @DecimalMin(value = "-180")
    @DecimalMax(value = "180")
    private BigDecimal longitude;

    @NotNull(message = "纬度不能为空")
    @DecimalMin(value = "-90")
    @DecimalMax(value = "90")
    private BigDecimal latitude;

    @Size(max = 256)
    @Schema(description = "文字地址（选填），与经纬度无关，用于展示")
    private String address;

    @Schema(description = "关联地图上已有候选地点")
    private Long confirmLinkPlaceId;

    @Schema(description = "为 true 时忽略 5 米内候选并强制新建")
    private Boolean confirmNew = false;
}
