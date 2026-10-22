package com.campus.travel;

import lombok.Data;

import java.util.List;

/** 路线查询结果；coordinates 一律 GCJ-02 */
@Data
public class RouteInfo {

    /** WALK RIDE DRIVE TAXI TRANSIT */
    private String mode;
    private Double distanceMeters;
    /** 预计耗时（秒）；展示层须标注"预计" */
    private Long durationSeconds;
    /** 站间折线 [[lng,lat],...]，可能为空 */
    private List<double[]> polyline = List.of();
    /** true 表示高德不可用时的本地估算值 */
    private boolean estimated;
    /** amap ─ 高德实际计算 / estimate ─ 本地直线估算 */
    private String provider;
}
