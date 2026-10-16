package com.campus.schedule.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 个人日程条目：多源忙碌视图的私有来源之一（忙碌计算与 activities 联合，不重复入表） */
@Data
public class PersonalSchedule {

    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String note;
    private LocalDateTime createdAt;
}
