package com.campus.map.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * P0.5 共建地图语义检索单行：某个地点在某张地图上的投影。
 * 同一地点可能出现在多张地图上，调用端按 placeId 聚合为 GroupPlaceContext。
 * noteDigest 为该（map, place）下备注聚合摘要（最新在前），仅作展示，不参与检索条件。
 */
@Data
public class MapPlaceSearchItem {

    private Long mapId;
    private Long id;
    private String name;
    private String description;
    private String address;
    private Long creatorId;
    private Long categoryId;
    private String categoryName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String noteDigest;
}
