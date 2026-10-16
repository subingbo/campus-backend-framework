package com.campus.map.mapper;

import com.campus.map.dto.MapPlaceSearchItem;
import com.campus.map.dto.PlaceMapItem;
import com.campus.map.entity.MapPlace;
import com.campus.place.entity.Place;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface MapPlaceMapper {

    int insert(MapPlace mapPlace);

    MapPlace selectByMapAndPlace(@Param("mapId") Long mapId, @Param("placeId") Long placeId);

    int deleteByMapAndPlace(@Param("mapId") Long mapId, @Param("placeId") Long placeId);

    List<Place> selectPlacesByMapId(@Param("mapId") Long mapId);

    List<Place> selectPlacesOnMapInBoundingBox(@Param("mapId") Long mapId,
                                               @Param("minLat") BigDecimal minLat,
                                               @Param("maxLat") BigDecimal maxLat,
                                               @Param("minLng") BigDecimal minLng,
                                               @Param("maxLng") BigDecimal maxLng);

    int existsOnMap(@Param("mapId") Long mapId, @Param("placeId") Long placeId);

    int countEditableMapsForUser(@Param("userId") Long userId, @Param("placeId") Long placeId);

    List<Long> selectMapIdsByPlaceId(@Param("placeId") Long placeId);

    /** 地点所在且当前用户是成员的地图（日历详情"去现场"用） */
    List<PlaceMapItem> selectMapsForPlace(@Param("placeId") Long placeId, @Param("userId") Long userId);

    /** P0.5 共建地图语义检索：名称/简介/分类名/群内备注四路匹配，全部参数绑定 */
    List<MapPlaceSearchItem> searchPlacesOnMaps(@Param("mapIds") List<Long> mapIds,
                                                @Param("pattern") String pattern,
                                                @Param("categoryId") Long categoryId,
                                                @Param("limit") int limit);

    /** P0.5 指定地点在给定地图集合内的投影行（含每张图的备注摘要），缺失的非成员行为空 */
    List<MapPlaceSearchItem> selectPlacesByMapAndPlaceIds(@Param("mapIds") List<Long> mapIds,
                                                          @Param("placeIds") List<Long> placeIds);
}
