package com.campus.ai.dto;

import lombok.Data;

/**
 * 客户端随消息上送的实时定位快照（GCJ-02）。
 * 仅注入本轮 Agent 工具语境（get_my_location），不落库、不参与任何业务存储；
 * 缺字段时按“未提供定位”处理，不报错。
 */
@Data
public class AiUserLocation {

    private Double longitude;
    private Double latitude;
    /** 定位精度（米），浏览器可能不返回 */
    private Double accuracy;
    /** 前端取样时刻（ISO 字符串），可空 */
    private String capturedAt;
    /** 来源：device（设备定位）| manual（用户在地图上手动纠正） */
    private String source;
}
