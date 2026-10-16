package com.campus.map.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MapListItemResponse {

    private Long id;
    private String name;
    private String description;
    private String coverImage;
    private String visibility;
    private String myRole;
    private int memberCount;
    private int placeCount;
    private LocalDateTime createdAt;
}
