package com.campus.schedule.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 日程共享授权：owner 授予 USER/GROUP 仅忙闲可见（FREE_BUSY）；撤销即删除行 */
@Data
public class ScheduleSharePermission {

    public static final String TARGET_USER = "USER";
    public static final String TARGET_GROUP = "GROUP";
    public static final String LEVEL_FREE_BUSY = "FREE_BUSY";

    private Long id;
    private Long ownerUserId;
    private String targetType;
    private Long targetId;
    private String permissionLevel;
    private LocalDateTime createdAt;
}
