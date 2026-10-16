package com.campus.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "保存草稿请求体（可选）")
public class PlanSaveRequest {

    @Schema(description = "期望的草稿版本号；与会话当前版本不一致时返回 409。不传则不校验（旧客户端兼容）")
    private Integer expectedPlanVersion;
}
