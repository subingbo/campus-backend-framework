package com.campus.map.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MapDetailResponse {

    private Long id;
    private Long groupId;
    private String name;
    private String description;
    private String coverImage;
    private String visibility;
    private String myRole;
    private int memberCount;
    private int placeCount;
    private String inviteCode;
    private List<MapMemberResponse> members = new ArrayList<>();
}
