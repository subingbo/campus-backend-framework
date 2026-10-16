package com.campus.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "分享已有地点到地图时的确认参数")
public class SharePlaceToMapRequest {

    @Schema(description = "关联目标地图上 5 米内已有地点")
    private Long confirmLinkPlaceId;

    @Schema(description = "忽略候选并强制关联当前地点")
    private Boolean confirmNew = false;
}
