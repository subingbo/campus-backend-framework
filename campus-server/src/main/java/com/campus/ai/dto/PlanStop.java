package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 规划站点；坐标一律 GCJ-02；transportMode 为前往该站的交通方式 */
@Data
public class PlanStop {

    private Integer seq;
    private String name;
    private Long placeId;
    private Double longitude;
    private Double latitude;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** WALK / RIDE / DRIVE / TRANSIT，可空 */
    private String transportMode;
    private String note;

    /** P1.5 来源标签：GROUP_MAP / PERSONAL_MAP / AMAP_POI（提交时按数据覆写，不信模型自述） */
    private String source;
    /** 该站停留（分钟） */
    private Integer stayMinutes;
    /** 来源共建地图（GROUP_MAP 时非空） */
    private Long mapId;
}
