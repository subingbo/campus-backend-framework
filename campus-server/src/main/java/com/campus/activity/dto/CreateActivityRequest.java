package com.campus.activity.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

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

    /** 活动类型：MEETING 会议 COMPETITION 比赛 OTHER 其他；缺省按会议处理 */
    @Pattern(regexp = "MEETING|COMPETITION|OTHER", message = "活动类型无效")
    private String category;

    /** 可选多站行程；主场地仍是 placeId，站点时间须落在活动起止内且按序 */
    @Valid
    @Size(max = 8, message = "行程站点最多 8 个")
    private List<ActivityStopRequest> stops;
}
