package com.campus.schedule.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 当前用户可按群授予/撤销 FREE_BUSY 的群地图选项。 */
@Data
public class ScheduleShareOption {

    private Long groupId;
    private String groupName;
    private Long mapId;
    private String mapName;
    private String visibility;
    /** 当前用户在该地图的角色：OWNER / EDITOR / VIEWER */
    private String myRole;
    private boolean granted;
    private LocalDateTime grantedAt;
}
