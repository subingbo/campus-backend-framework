package com.campus.activity.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 行程站点出参：placeId 非空时 placeName 来自地点表 */
@Data
public class ActivityStopItem {

    private Long activityId;
    private Integer seq;
    private String name;
    private Long placeId;
    private String placeName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String transportMode;
    private String note;
}
