package com.campus.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 本轮 Agent 的地理事件：工具查到/路过的地点、路线、用户位置。
 * 前端据此实时上图（标记/折线/蓝点）并做可点击联动；同一份 JSON 随助手消息落库（ai_message.geo_json）用于刷新回放。
 * 坐标一律 GCJ-02；折线已做抽稀（≤200 点）。
 */
@Data
public class GeoEvent {

    public static final String TYPE_PLACES = "PLACES";
    public static final String TYPE_ROUTE = "ROUTE";
    public static final String TYPE_ITINERARY = "ITINERARY";
    public static final String TYPE_USER_LOCATION = "USER_LOCATION";

    /** 来源标签（前端徽标配色用）：共建/收藏/校园库/外部POI/我的位置/路线 */
    public static final String SOURCE_GROUP_MAP = "GROUP_MAP";
    public static final String SOURCE_PERSONAL_MAP = "PERSONAL_MAP";
    public static final String SOURCE_CAMPUS_LIB = "CAMPUS_LIB";
    public static final String SOURCE_AMAP_POI = "AMAP_POI";
    public static final String SOURCE_USER_LOCATION = "USER_LOCATION";
    public static final String SOURCE_ROUTE = "ROUTE";

    private String type;
    /** 产生该事件的工具名（search_group_map_places / route_info / plan_itinerary ...） */
    private String tool;
    private String source;
    private String note;
    private List<GeoPlace> places = new ArrayList<>();
    private GeoRoute route;
    private List<GeoRouteSegment> segments = new ArrayList<>();

    /** 单个地点（统一承载共建/收藏/校园库/POI） */
    @Data
    public static class GeoPlace {
        private Long placeId;
        private String name;
        private String address;
        private String categoryName;
        private Double longitude;
        private Double latitude;
        private Double distanceMeters;
        private List<Long> mapIds;
        /** 成员备注摘要（共建地图语境） */
        private String noteDigest;
        private String source;
    }

    /** 单段路线（route_info） */
    @Data
    public static class GeoRoute {
        private String mode;
        private Double distanceMeters;
        private Long durationSeconds;
        private Boolean estimated;
        private String provider;
        private List<double[]> polyline = new ArrayList<>();
        private double[] from;
        private double[] to;
    }

    /** 方案内站点间交通段（plan_itinerary） */
    @Data
    public static class GeoRouteSegment {
        private Integer fromSeq;
        private Integer toSeq;
        private String transportMode;
        private Double distanceMeters;
        private Integer durationMinutes;
        private Boolean estimated;
        private List<double[]> polyline = new ArrayList<>();
    }
}
