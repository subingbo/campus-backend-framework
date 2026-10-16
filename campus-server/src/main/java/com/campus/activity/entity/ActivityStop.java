package com.campus.activity.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 多站活动行程站点；坐标与 place 表同一基准（GCJ-02） */
@Data
public class ActivityStop {

    private Long id;
    private Long activityId;
    /** 站点顺序，从 1 递增 */
    private Integer seq;
    private String name;
    /** 关联地点ID，可空（外部 POI 坐标直存） */
    private Long placeId;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** 前往本站的交通方式：WALK RIDE DRIVE TRANSIT */
    private String transportMode;
    private String note;
}
