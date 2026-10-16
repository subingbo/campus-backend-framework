package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** P0.5 群体共建地图上下文条目（get_group_map_context 工具返回项） */
@Data
public class GroupMapSummary {

    private Long mapId;
    private Long groupId;
    private String name;
    private String description;
    private int placeCount;
    /** 来源标签：GROUP_MAP / PERSONAL_MAP */
    private String source;
    private LocalDateTime updatedAt;
}
