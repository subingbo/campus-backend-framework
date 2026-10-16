package com.campus.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 编辑活动：所有字段可选，未提供的字段保持不变 */
@Data
public class UpdateActivityRequest {

    @Size(max = 128)
    private String title;

    @Size(max = 1000)
    private String description;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    @Min(1)
    @Max(500)
    private Integer maxMembers;

    private String category;

    /** 提供时整体替换行程站点（删旧插新）；不提供保持不变 */
    @Valid
    @Size(max = 8, message = "行程站点最多 8 个")
    private List<ActivityStopRequest> stops;
}
