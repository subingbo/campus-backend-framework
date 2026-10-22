package com.campus.travel;

import java.util.List;

/**
 * 交通信息提供方（中立能力层）。实现类负责对接具体地图厂商；
 * 换厂商只改实现，不触碰 activity/ai 调用方，也不往数据库写任何厂商字段。
 */
public interface TravelInfoProvider {

    /**
     * 两条坐标之间的路线（预计耗时/距离/折线）。
     *
     * @param mode WALK / RIDE / DRIVE / TAXI / TRANSIT
     */
    RouteInfo searchRoute(String mode, double fromLng, double fromLat, double toLng, double toLat);

    /**
     * 关键字 POI 检索（地图上没有的地点）；无结果返回空列表，不编造地点。
     */
    List<PoiItem> searchPoi(String keyword, Double nearLng, Double nearLat, Integer radiusMeters);
}
