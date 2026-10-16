package com.campus.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 行程站点入参：placeId 与坐标二选一；时间可选但须落在活动起止内且按序 */
@Data
public class ActivityStopRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    private Long placeId;

    private BigDecimal longitude;

    private BigDecimal latitude;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /** 前往本站的交通方式：WALK RIDE DRIVE TRANSIT */
    @Pattern(regexp = "WALK|RIDE|DRIVE|TRANSIT", message = "交通方式无效")
    private String transportMode;

    @Size(max = 256)
    private String note;
}
