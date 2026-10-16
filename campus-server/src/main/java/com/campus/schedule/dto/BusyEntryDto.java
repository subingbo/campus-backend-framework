package com.campus.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 单条忙碌占用：sourceType=SCHEDULE(个人日程) | ACTIVITY(已报名/已创建活动) */
@Data
public class BusyEntryDto {

    private Long userId;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String sourceType;
    private Long sourceId;
    /** 仅 owner 本人可见；对 FREE_BUSY 授权查看者恒为 null（§8.1 不泄露内容） */
    private String title;
}
