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
import com.campus.map.dto.SharePlaceToMapRequest;
import com.campus.map.dto.UpdateMemberRoleRequest;

import java.util.List;

public interface SharedMapService {

    GroupCreateResponse createGroup(CreateGroupRequest request);

    MapDetailResponse joinGroup(JoinGroupRequest request);

    List<MapListItemResponse> listMyMaps();

    MapDetailResponse getMapDetail(Long mapId);

    void updateMemberRole(Long mapId, Long userId, UpdateMemberRoleRequest request);

    void removeMember(Long mapId, Long userId);

    List<MapPlaceItemResponse> listMapPlaces(Long mapId);

    MapPlaceAddResponse addPlaceToMap(Long mapId, AddMapPlaceRequest request);

    MapPlaceAddResponse sharePlaceToMap(Long mapId, Long placeId, SharePlaceToMapRequest request);

    void removePlaceFromMap(Long mapId, Long placeId);

    List<PlaceCommentResponse> listPlaceComments(Long mapId, Long placeId);

    PlaceCommentResponse addPlaceComment(Long mapId, Long placeId, CreatePlaceCommentRequest request);
}
