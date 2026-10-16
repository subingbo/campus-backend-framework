package com.campus.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import lombok.Data;

@Data
@Schema(description = "修订规划草稿请求")
public class RevisePlanRequest {

    @Valid
    @Schema(description = "编辑后的完整草稿（站点按序）")
    private PlanDto plan;

    @Schema(description = "期望的草稿版本号；与会话当前版本不一致时返回 409（防双端覆盖）。不传则按当前版本")
    private Integer expectedPlanVersion;

    @Schema(description = "另建新活动（复制为新草稿）：已保存会话的修订默认走活动更新接口；置 true 时清空谱系，再次保存将创建新活动")
    private Boolean asNewDraft;
}
