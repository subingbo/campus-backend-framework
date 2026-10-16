package com.campus.schedule.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "设置/撤销日程共享授权（FREE_BUSY 仅忙闲）")
public class ShareScheduleRequest {

    @NotBlank
    @Schema(description = "USER（个人）或 GROUP（campus_group.id）")
    private String targetType;

    @NotNull
    private Long targetId;

    @Schema(description = "true=授予 FREE_BUSY；false=撤销")
    private Boolean grant;
}
