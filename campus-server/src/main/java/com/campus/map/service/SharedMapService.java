package com.campus.map.service;

import com.campus.map.dto.AddMapPlaceRequest;
import com.campus.map.dto.CreateGroupRequest;
import com.campus.map.dto.CreatePlaceCommentRequest;
import com.campus.map.dto.GroupCreateResponse;
import com.campus.map.dto.JoinGroupRequest;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.dto.MapListItemResponse;
import com.campus.map.dto.MapPlaceAddResponse;
import com.campus.map.dto.MapPlaceItemResponse;
import com.campus.map.dto.PlaceCommentResponse;
import com.campus.map.dto.PlaceMapItem;
import com.campus.map.dto.SharePlaceToMapRequest;
import com.campus.map.dto.UpdateMemberRoleRequest;

import java.util.List;

public interface SharedMapService {

    GroupCreateResponse createGroup(CreateGroupRequest request);

    MapDetailResponse joinGroup(JoinGroupRequest request);

    List<MapListItemResponse> listMyMaps();

    MapDetailResponse getMapDetail(Long mapId);

    /** 指定查看者身份的地图详情（Agent 工具线程没有 SecurityContext，规划校验用显式 userId） */
    MapDetailResponse getMapDetailAs(Long mapId, Long userId);

    void updateMemberRole(Long mapId, Long userId, UpdateMemberRoleRequest request);

    void removeMember(Long mapId, Long userId);

    List<MapPlaceItemResponse> listMapPlaces(Long mapId);

    MapPlaceAddResponse addPlaceToMap(Long mapId, AddMapPlaceRequest request);

    MapPlaceAddResponse sharePlaceToMap(Long mapId, Long placeId, SharePlaceToMapRequest request);

    void removePlaceFromMap(Long mapId, Long placeId);

    List<PlaceCommentResponse> listPlaceComments(Long mapId, Long placeId);

    PlaceCommentResponse addPlaceComment(Long mapId, Long placeId, CreatePlaceCommentRequest request);

    /** 地点所在且当前用户是成员的地图（活动详情"去现场"） */
    List<PlaceMapItem> listMapsForPlace(Long placeId);
}
