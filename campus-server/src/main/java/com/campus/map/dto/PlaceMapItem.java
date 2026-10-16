package com.campus.map.dto;

import lombok.Data;

/** 地点所在地图（仅当前用户是成员的），用于"从活动跳到地图现场" */
@Data
public class PlaceMapItem {

    private Long id;
    private String name;
    /** 当前用户在该地图的角色：OWNER / EDITOR / VIEWER */
    private String myRole;
}
