package com.campus.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "修订结果：规范化草稿 + 新版本号")
public class RevisePlanResponse {

    private PlanDto plan;

    @Schema(description = "修订后的草稿版本号（旧版本+1）")
    private Integer planVersion;
}
