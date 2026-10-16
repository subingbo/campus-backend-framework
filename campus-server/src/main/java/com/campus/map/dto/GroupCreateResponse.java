package com.campus.map.dto;

import lombok.Data;

@Data
public class GroupCreateResponse {

    private Long groupId;
    private Long mapId;
    private String inviteCode;
    private MapDetailResponse map;
}
