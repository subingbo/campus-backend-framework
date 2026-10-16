package com.campus.ai.travel;

import lombok.Data;

/** 外部 POI 检索结果；coordinates 一律 GCJ-02 */
@Data
public class PoiItem {

    private String name;
    private String address;
    private double longitude;
    private double latitude;
    /** 距检索中心点的距离（米），可能为空 */
    private Double distanceMeters;
}
