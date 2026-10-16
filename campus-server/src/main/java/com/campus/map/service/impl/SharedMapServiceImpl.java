package com.campus.map.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.geo.GeoCandidateQuery;
import com.campus.common.result.ResultCode;
import com.campus.map.constant.MapRole;
import com.campus.map.constant.MapVisibility;
import com.campus.map.dto.AddMapPlaceRequest;
import com.campus.map.dto.CreateGroupRequest;
import com.campus.map.dto.CreatePlaceCommentRequest;
import com.campus.map.dto.GroupCreateResponse;
import com.campus.map.dto.JoinGroupRequest;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.dto.MapListItemResponse;
import com.campus.map.dto.MapMemberResponse;
import com.campus.map.dto.MapPlaceAddResponse;
import com.campus.map.dto.MapPlaceItemResponse;
import com.campus.map.dto.PlaceCommentResponse;
import com.campus.map.dto.PlaceMapItem;
import com.campus.map.dto.SharePlaceToMapRequest;
import com.campus.map.dto.UpdateMemberRoleRequest;
import com.campus.map.entity.CampusGroup;
import com.campus.map.entity.MapMember;
import com.campus.map.entity.MapPlace;
import com.campus.map.entity.PlaceComment;
import com.campus.map.entity.SharedMap;
import com.campus.map.mapper.CampusGroupMapper;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.map.mapper.MapPlaceMapper;
import com.campus.map.mapper.PlaceCommentMapper;
import com.campus.map.mapper.SharedMapMapper;
import com.campus.map.service.SharedMapService;
import com.campus.place.dto.PlaceBriefResponse;
import com.campus.place.entity.Place;
import com.campus.place.entity.PlaceCategory;
import com.campus.place.mapper.PlaceCategoryMapper;
import com.campus.place.mapper.PlaceMapper;
import com.campus.place.service.PlaceService;
import com.campus.security.user.SecurityUtils;
import com.campus.ws.MapEventNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SharedMapServiceImpl implements SharedMapService {

    private static final double MERGE_RADIUS_METERS = 5.0;
    private static final char[] INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CampusGroupMapper campusGroupMapper;
    private final SharedMapMapper sharedMapMapper;
    private final MapMemberMapper mapMemberMapper;
    private final MapPlaceMapper mapPlaceMapper;
    private final PlaceCommentMapper placeCommentMapper;
    private final PlaceMapper placeMapper;
    private final PlaceCategoryMapper placeCategoryMapper;
    private final PlaceService placeService;
    private final MapEventNotifier mapEventNotifier;

    @Override
    @Transactional
    public GroupCreateResponse createGroup(CreateGroupRequest request) {
        Long userId = SecurityUtils.getUserId();
        String visibility = normalizeVisibility(request.getVisibility());
        String mapName = StringUtils.hasText(request.getMapName()) ? request.getMapName().trim() : request.getName().trim();

        CampusGroup group = new CampusGroup();
        group.setName(request.getName().trim());
        group.setDescription(request.getDescription());
        group.setOwnerId(userId);
        group.setInviteCode(generateInviteCode());
        group.setVisibility(visibility);
        campusGroupMapper.insert(group);

        SharedMap map = new SharedMap();
        map.setGroupId(group.getId());
        map.setName(mapName);
        map.setDescription(request.getDescription());
        sharedMapMapper.insert(map);

        MapMember owner = new MapMember();
        owner.setMapId(map.getId());
        owner.setUserId(userId);
        owner.setRole(MapRole.OWNER.name());
        mapMemberMapper.insert(owner);

        GroupCreateResponse response = new GroupCreateResponse();
        response.setGroupId(group.getId());
        response.setMapId(map.getId());
        response.setInviteCode(group.getInviteCode());
        response.setMap(buildMapDetail(map.getId(), userId, true));
        return response;
    }

    @Override
    @Transactional
    public MapDetailResponse joinGroup(JoinGroupRequest request) {
        Long userId = SecurityUtils.getUserId();
        CampusGroup group = campusGroupMapper.selectByInviteCode(request.getInviteCode().trim().toUpperCase());
        if (group == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "邀请码无效");
        }
        SharedMap map = sharedMapMapper.selectByGroupId(group.getId());
        if (map == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "地图数据异常");
        }
        MapMember existing = mapMemberMapper.selectByMapAndUser(map.getId(), userId);
        if (existing == null) {
            MapMember member = new MapMember();
            member.setMapId(map.getId());
            member.setUserId(userId);
            member.setRole(MapRole.EDITOR.name());
            mapMemberMapper.insert(member);
        }
        return buildMapDetail(map.getId(), userId, true);
    }

    @Override
    public List<MapListItemResponse> listMyMaps() {
        return sharedMapMapper.selectByUserId(SecurityUtils.getUserId());
    }

    @Override
    public MapDetailResponse getMapDetail(Long mapId) {
        return buildMapDetail(mapId, SecurityUtils.getUserId(), true);
    }

    @Override
    public MapDetailResponse getMapDetailAs(Long mapId, Long userId) {
        return buildMapDetail(mapId, userId, true);
    }

    @Override
    @Transactional
    public void updateMemberRole(Long mapId, Long targetUserId, UpdateMemberRoleRequest request) {
        Long operatorId = SecurityUtils.getUserId();
        MapMember operator = requireMember(mapId, operatorId);
        requireRole(operator, MapRole.OWNER);
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能修改自己的角色");
        }
        MapMember target = mapMemberMapper.selectByMapAndUser(mapId, targetUserId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "成员不存在");
        }
        if (MapRole.OWNER.name().equals(target.getRole())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能修改群主角色");
        }
        MapRole newRole = parseRole(request.getRole());
        if (newRole == MapRole.OWNER) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能将成员设为群主");
        }
        mapMemberMapper.updateRole(mapId, targetUserId, newRole.name());
    }

    @Override
    @Transactional
    public void removeMember(Long mapId, Long targetUserId) {
        Long operatorId = SecurityUtils.getUserId();
        MapMember operator = requireMember(mapId, operatorId);
        requireRole(operator, MapRole.OWNER);
        if (operatorId.equals(targetUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能移除自己");
        }
        MapMember target = mapMemberMapper.selectByMapAndUser(mapId, targetUserId);
        if (target == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "成员不存在");
        }
        if (MapRole.OWNER.name().equals(target.getRole())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能移除群主");
        }
        mapMemberMapper.deleteByMapAndUser(mapId, targetUserId);
    }

    @Override
    public List<MapPlaceItemResponse> listMapPlaces(Long mapId) {
        assertCanView(mapId, SecurityUtils.getUserId());
        List<Place> places = mapPlaceMapper.selectPlacesByMapId(mapId);
        List<MapPlaceItemResponse> result = new ArrayList<>();
        for (Place place : places) {
            result.add(toMapPlaceItem(place));
        }
        return result;
    }

    @Override
    @Transactional
    public MapPlaceAddResponse addPlaceToMap(Long mapId, AddMapPlaceRequest request) {
        Long userId = SecurityUtils.getUserId();
        requireEditor(mapId, userId);
        validateCategory(request.getCategoryId());

        if (request.getConfirmLinkPlaceId() != null) {
            return linkExistingPlace(mapId, userId, request.getConfirmLinkPlaceId(),
                    request.getLongitude(), request.getLatitude());
        }

        List<PlaceBriefResponse> candidates = findCandidatesOnMap(
                mapId, request.getLongitude(), request.getLatitude(), null);
        boolean confirmNew = Boolean.TRUE.equals(request.getConfirmNew());
        if (!candidates.isEmpty() && !confirmNew) {
            return pendingDuplicate(candidates);
        }

        Place place = new Place();
        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setCategoryId(request.getCategoryId());
        place.setLongitude(request.getLongitude());
        place.setLatitude(request.getLatitude());
        place.setAddress(request.getAddress());
        place.setCreatorId(userId);
        place.setStatus(1);
        placeMapper.insert(place);
        insertMapPlace(mapId, place.getId(), userId);
        return successResponse(place);
    }

    @Override
    @Transactional
    public MapPlaceAddResponse sharePlaceToMap(Long mapId, Long placeId, SharePlaceToMapRequest request) {
        Long userId = SecurityUtils.getUserId();
        requireEditor(mapId, userId);
        Place place = placeService.requireActivePlace(placeId);
        SharePlaceToMapRequest body = request == null ? new SharePlaceToMapRequest() : request;

        if (mapPlaceMapper.existsOnMap(mapId, placeId) > 0) {
            return successResponse(place);
        }

        if (body.getConfirmLinkPlaceId() != null) {
            return linkExistingPlace(mapId, userId, body.getConfirmLinkPlaceId(),
                    place.getLongitude(), place.getLatitude());
        }

        List<PlaceBriefResponse> candidates = findCandidatesOnMap(
                mapId, place.getLongitude(), place.getLatitude(), placeId);
        boolean confirmNew = Boolean.TRUE.equals(body.getConfirmNew());
        if (!candidates.isEmpty() && !confirmNew) {
            return pendingDuplicate(candidates);
        }

        insertMapPlace(mapId, placeId, userId);
        return successResponse(place);
    }

    @Override
    @Transactional
    public void removePlaceFromMap(Long mapId, Long placeId) {
        Long userId = SecurityUtils.getUserId();
        requireEditor(mapId, userId);
        Place place = placeService.requireActivePlace(placeId);
        if (mapPlaceMapper.selectByMapAndPlace(mapId, placeId) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "地点不在该地图上");
        }
        mapPlaceMapper.deleteByMapAndPlace(mapId, placeId);
        mapEventNotifier.notifyPlaceChanged(mapId, placeId, place.getName(), "移除了地点", userId);
    }

    @Override
    public List<PlaceCommentResponse> listPlaceComments(Long mapId, Long placeId) {
        assertCanView(mapId, SecurityUtils.getUserId());
        assertPlaceOnMap(mapId, placeId);
        return placeCommentMapper.selectByMapAndPlace(mapId, placeId);
    }

    @Override
    @Transactional
    public PlaceCommentResponse addPlaceComment(Long mapId, Long placeId, CreatePlaceCommentRequest request) {
        Long userId = SecurityUtils.getUserId();
        requireMember(mapId, userId);
        assertPlaceOnMap(mapId, placeId);

        PlaceComment comment = new PlaceComment();
        comment.setMapId(mapId);
        comment.setPlaceId(placeId);
        comment.setUserId(userId);
        comment.setContent(request.getContent().trim());
        placeCommentMapper.insert(comment);

        Place place = placeMapper.selectById(placeId);
        mapEventNotifier.notifyPlaceChanged(mapId, placeId,
                place != null ? place.getName() : "", "评论了地点", userId);

        List<PlaceCommentResponse> list = placeCommentMapper.selectByMapAndPlace(mapId, placeId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    @Override
    public List<PlaceMapItem> listMapsForPlace(Long placeId) {
        return mapPlaceMapper.selectMapsForPlace(placeId, SecurityUtils.getUserId());
    }

    private MapPlaceAddResponse linkExistingPlace(Long mapId, Long userId, Long placeId,
                                                  BigDecimal longitude, BigDecimal latitude) {
        Place place = placeService.requireActivePlace(placeId);
        if (mapPlaceMapper.existsOnMap(mapId, placeId) > 0) {
            return successResponse(place);
        }
        List<PlaceBriefResponse> nearby = findCandidatesOnMap(mapId, longitude, latitude, null);
        boolean allowed = nearby.stream().anyMatch(c -> c.getId().equals(placeId));
        if (!allowed) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "所选地点不在本图 5 米候选内");
        }
        insertMapPlace(mapId, placeId, userId);
        return successResponse(place);
    }

    private MapPlaceAddResponse pendingDuplicate(List<PlaceBriefResponse> candidates) {
        MapPlaceAddResponse response = new MapPlaceAddResponse();
        response.setAdded(false);
        response.setDuplicatePending(true);
        response.setCandidates(candidates);
        return response;
    }

    private MapPlaceAddResponse successResponse(Place place) {
        MapPlaceAddResponse response = new MapPlaceAddResponse();
        response.setAdded(true);
        response.setDuplicatePending(false);
        response.setPlace(toMapPlaceItem(place));
        return response;
    }

    private void insertMapPlace(Long mapId, Long placeId, Long userId) {
        MapPlace link = new MapPlace();
        link.setMapId(mapId);
        link.setPlaceId(placeId);
        link.setCreatedBy(userId);
        mapPlaceMapper.insert(link);
        // 添加/分享/关联同走此入口：统一在此触发地图成员的实时通知
        Place linked = placeMapper.selectById(placeId);
        mapEventNotifier.notifyPlaceChanged(mapId, placeId,
                linked != null ? linked.getName() : "", "把地点加进了地图", userId);
    }

    private List<PlaceBriefResponse> findCandidatesOnMap(Long mapId, BigDecimal longitude, BigDecimal latitude,
                                                         Long excludePlaceId) {
        GeoCandidateQuery.BoundingBox box = GeoCandidateQuery.boundingBox(longitude, latitude, MERGE_RADIUS_METERS);
        List<Place> raw = mapPlaceMapper.selectPlacesOnMapInBoundingBox(
                mapId, box.minLat(), box.maxLat(), box.minLng(), box.maxLng());
        List<GeoCandidateQuery.PlaceDistance> filtered = GeoCandidateQuery.filterWithinMeters(
                raw,
                longitude.doubleValue(),
                latitude.doubleValue(),
                MERGE_RADIUS_METERS,
                excludePlaceId);
        List<PlaceBriefResponse> result = new ArrayList<>();
        for (GeoCandidateQuery.PlaceDistance item : filtered) {
            result.add(toBrief(item.place(), item.distanceMeters()));
        }
        return result;
    }

    private PlaceBriefResponse toBrief(Place place, Double distanceMeters) {
        PlaceCategory category = placeCategoryMapper.selectById(place.getCategoryId());
        PlaceBriefResponse brief = new PlaceBriefResponse();
        brief.setId(place.getId());
        brief.setName(place.getName());
        brief.setCategoryId(place.getCategoryId());
        brief.setCategoryName(category != null ? category.getName() : null);
        brief.setLongitude(place.getLongitude());
        brief.setLatitude(place.getLatitude());
        brief.setDistanceMeters(distanceMeters);
        return brief;
    }

    private MapPlaceItemResponse toMapPlaceItem(Place place) {
        PlaceCategory category = placeCategoryMapper.selectById(place.getCategoryId());
        MapPlaceItemResponse item = new MapPlaceItemResponse();
        item.setId(place.getId());
        item.setName(place.getName());
        item.setDescription(place.getDescription());
        item.setCategoryId(place.getCategoryId());
        item.setCategoryName(category != null ? category.getName() : null);
        item.setLongitude(place.getLongitude());
        item.setLatitude(place.getLatitude());
        item.setAddress(place.getAddress());
        item.setCreatorId(place.getCreatorId());
        return item;
    }

    private MapDetailResponse buildMapDetail(Long mapId, Long userId, boolean includeMembers) {
        SharedMap map = sharedMapMapper.selectById(mapId);
        if (map == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "地图不存在");
        }
        CampusGroup group = campusGroupMapper.selectById(map.getGroupId());
        if (group == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "群组不存在");
        }
        MapMember membership = mapMemberMapper.selectByMapAndUser(mapId, userId);
        if (membership == null && MapVisibility.MEMBERS.equals(group.getVisibility())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        MapDetailResponse detail = new MapDetailResponse();
        detail.setId(map.getId());
        detail.setGroupId(group.getId());
        detail.setName(map.getName());
        detail.setDescription(map.getDescription());
        detail.setCoverImage(map.getCoverImage());
        detail.setVisibility(group.getVisibility());
        detail.setMyRole(membership != null ? membership.getRole() : null);
        detail.setMemberCount(sharedMapMapper.countMembers(mapId));
        detail.setPlaceCount(sharedMapMapper.countPlaces(mapId));
        if (membership != null && MapRole.OWNER.name().equals(membership.getRole())) {
            detail.setInviteCode(group.getInviteCode());
        }
        if (includeMembers && membership != null) {
            detail.setMembers(mapMemberMapper.selectByMapId(mapId));
        }
        return detail;
    }

    private void assertCanView(Long mapId, Long userId) {
        SharedMap map = sharedMapMapper.selectById(mapId);
        if (map == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "地图不存在");
        }
        CampusGroup group = campusGroupMapper.selectById(map.getGroupId());
        if (group == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "群组不存在");
        }
        MapMember membership = mapMemberMapper.selectByMapAndUser(mapId, userId);
        if (membership == null && MapVisibility.MEMBERS.equals(group.getVisibility())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private MapMember requireMember(Long mapId, Long userId) {
        MapMember member = mapMemberMapper.selectByMapAndUser(mapId, userId);
        if (member == null) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是地图成员");
        }
        return member;
    }

    private void requireEditor(Long mapId, Long userId) {
        MapMember member = requireMember(mapId, userId);
        requireRole(member, MapRole.EDITOR);
    }

    private void requireRole(MapMember member, MapRole required) {
        MapRole role = parseRole(member.getRole());
        if (!role.atLeast(required)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
    }

    private MapRole parseRole(String role) {
        try {
            return MapRole.fromCode(role);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "无效角色");
        }
    }

    private void assertPlaceOnMap(Long mapId, Long placeId) {
        if (mapPlaceMapper.existsOnMap(mapId, placeId) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "地点不在该地图上");
        }
    }

    private void validateCategory(Long categoryId) {
        PlaceCategory category = placeCategoryMapper.selectById(categoryId);
        if (category == null || category.getStatus() == null || category.getStatus() != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "地点分类无效");
        }
    }

    private String normalizeVisibility(String visibility) {
        if (!StringUtils.hasText(visibility)) {
            return MapVisibility.MEMBERS;
        }
        String value = visibility.trim().toUpperCase();
        if (!MapVisibility.MEMBERS.equals(value) && !MapVisibility.PUBLIC.equals(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "可见性无效");
        }
        return value;
    }

    private String generateInviteCode() {
        for (int attempt = 0; attempt < 30; attempt++) {
            StringBuilder code = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                code.append(INVITE_CHARS[RANDOM.nextInt(INVITE_CHARS.length)]);
            }
            String candidate = code.toString();
            if (campusGroupMapper.selectByInviteCode(candidate) == null) {
                return candidate;
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "无法生成邀请码");
    }
}
