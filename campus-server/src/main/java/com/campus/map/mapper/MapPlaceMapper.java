package com.campus.map.mapper;

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
}
