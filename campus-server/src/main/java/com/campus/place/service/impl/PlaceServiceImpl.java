package com.campus.place.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.geo.GeoCandidateQuery;
import com.campus.common.result.ResultCode;
import com.campus.place.cache.PlaceDetailCache;
import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.dto.PlaceBriefResponse;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.dto.PlaceDetailResponse;
import com.campus.place.dto.UpdatePlaceRequest;
import com.campus.place.entity.Place;
import com.campus.place.entity.PlaceCategory;
import com.campus.place.mapper.PlaceCategoryMapper;
import com.campus.place.mapper.PlaceMapper;
import com.campus.place.service.PlaceService;
import com.campus.map.mapper.MapPlaceMapper;
import com.campus.post.dto.PostListItemResponse;
import com.campus.post.mapper.PostLikeMapper;
import com.campus.post.mapper.PostMapper;
import com.campus.security.user.SecurityUtils;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.ws.MapEventNotifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaceServiceImpl implements PlaceService {

    private static final double MERGE_RADIUS_METERS = 5.0;
    private static final int DEFAULT_RADIUS = 500;
    private static final int MAX_RADIUS = 5000;

    private final PlaceMapper placeMapper;
    private final PlaceCategoryMapper placeCategoryMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final PostLikeMapper postLikeMapper;
    private final PlaceDetailCache placeDetailCache;
    private final MapPlaceMapper mapPlaceMapper;
    private final MapEventNotifier mapEventNotifier;

    @Override
    @Transactional
    public PlaceCreateResponse createPlace(CreatePlaceRequest request) {
        validateCategory(request.getCategoryId());
        boolean confirmNew = Boolean.TRUE.equals(request.getConfirmNew());
        List<PlaceBriefResponse> candidates = findCandidatesWithinMeters(
                request.getLongitude(), request.getLatitude(), MERGE_RADIUS_METERS);

        PlaceCreateResponse response = new PlaceCreateResponse();
        if (!candidates.isEmpty() && !confirmNew) {
            response.setCreated(false);
            response.setPlace(null);
            response.setCandidates(candidates);
            return response;
        }

        Place place = new Place();
        place.setName(request.getName());
        place.setDescription(request.getDescription());
        place.setCategoryId(request.getCategoryId());
        place.setLongitude(request.getLongitude());
        place.setLatitude(request.getLatitude());
        place.setAddress(request.getAddress());
        place.setCreatorId(SecurityUtils.getUserId());
        place.setStatus(1);
        placeMapper.insert(place);

        response.setCreated(true);
        response.setPlace(buildDetail(place.getId(), SecurityUtils.getUserId()));
        response.setCandidates(List.of());
        return response;
    }

    @Override
    public List<PlaceBriefResponse> nearby(BigDecimal longitude, BigDecimal latitude, Integer radius) {
        int radiusMeters = radius == null ? DEFAULT_RADIUS : Math.min(Math.max(radius, 1), MAX_RADIUS);
        return findWithinRadius(longitude, latitude, radiusMeters);
    }

    @Override
    public PlaceDetailResponse getDetail(Long id) {
        Long currentUserId = SecurityUtils.getUserId();
        PlaceDetailResponse cached = placeDetailCache.get(id);
        if (cached != null) {
            applyLiked(cached, currentUserId);
            return cached;
        }
        Place place = placeMapper.selectById(id);
        if (place == null || place.getStatus() == null || place.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        PlaceDetailResponse detail = buildDetail(id, null);
        placeDetailCache.put(detail);
        applyLiked(detail, currentUserId);
        return detail;
    }

    @Override
    public List<PlaceBriefResponse> map(BigDecimal minLng, BigDecimal maxLng, BigDecimal minLat, BigDecimal maxLat) {
        if (minLng == null || maxLng == null || minLat == null || maxLat == null
                || minLng.compareTo(maxLng) > 0 || minLat.compareTo(maxLat) > 0
                || minLng.doubleValue() < -180 || maxLng.doubleValue() > 180
                || minLat.doubleValue() < -90 || maxLat.doubleValue() > 90) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "地图范围无效");
        }
        List<Place> places = placeMapper.selectInBoundingBoxLimited(minLat, maxLat, minLng, maxLng, 200);
        List<PlaceBriefResponse> result = new ArrayList<>();
        for (Place place : places) {
            result.add(toBrief(place, null));
        }
        return result;
    }

    @Override
    public void hidePlace(Long id) {
        Place place = placeMapper.selectById(id);
        if (place == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        placeMapper.updateStatus(id, 0);
        placeDetailCache.evict(id);
    }

    @Override
    public Place requireActivePlace(Long id) {
        Place place = placeMapper.selectById(id);
        if (place == null || place.getStatus() == null || place.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "地点不存在或已隐藏");
        }
        return place;
    }

    @Override
    @Transactional
    public PlaceDetailResponse updatePlace(Long id, UpdatePlaceRequest request) {
        Long userId = SecurityUtils.getUserId();
        Place place = requireActivePlace(id);
        if (mapPlaceMapper.countEditableMapsForUser(userId, id) == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权编辑该地点");
        }
        validateCategory(request.getCategoryId());
        place.setName(request.getName().trim());
        place.setDescription(request.getDescription());
        place.setCategoryId(request.getCategoryId());
        place.setAddress(request.getAddress());
        placeMapper.update(place);
        placeDetailCache.evict(id);
        // 地点信息变更 → 推给所有包含该地点的地图的在线成员
        for (Long mapId : mapPlaceMapper.selectMapIdsByPlaceId(id)) {
            mapEventNotifier.notifyPlaceChanged(mapId, id, place.getName(), "修改了地点信息", userId);
        }
        return buildDetail(id, userId);
    }

    @Override
    public boolean canUserEditPlace(Long userId, Long placeId) {
        return mapPlaceMapper.countEditableMapsForUser(userId, placeId) > 0;
    }

    private PlaceDetailResponse buildDetail(Long placeId, Long currentUserId) {
        Place place = placeMapper.selectById(placeId);
        PlaceCategory category = placeCategoryMapper.selectById(place.getCategoryId());
        User creator = userMapper.selectById(place.getCreatorId());

        PlaceDetailResponse detail = new PlaceDetailResponse();
        detail.setId(place.getId());
        detail.setName(place.getName());
        detail.setDescription(place.getDescription());
        detail.setCategoryId(place.getCategoryId());
        detail.setCategoryName(category != null ? category.getName() : null);
        detail.setLongitude(place.getLongitude());
        detail.setLatitude(place.getLatitude());
        detail.setAddress(place.getAddress());
        detail.setCreatorId(place.getCreatorId());
        detail.setCreatorNickname(creator != null ? creator.getNickname() : null);
        detail.setPostCount(placeMapper.countPosts(placeId));
        detail.setMemberCount(placeMapper.countMembers(placeId));
        detail.setLikeCount(placeMapper.countLikes(placeId));
        detail.setCreatedAt(place.getCreatedAt());

        detail.setLatestPosts(postMapper.selectLatestByPlaceId(placeId, currentUserId, 5));
        return detail;
    }

    private void validateCategory(Long categoryId) {
        PlaceCategory category = placeCategoryMapper.selectById(categoryId);
        if (category == null || category.getStatus() == null || category.getStatus() != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "地点分类无效");
        }
    }

    private List<PlaceBriefResponse> findCandidatesWithinMeters(BigDecimal longitude, BigDecimal latitude, double meters) {
        return findWithinRadius(longitude, latitude, (int) Math.ceil(meters));
    }

    private List<PlaceBriefResponse> findWithinRadius(BigDecimal longitude, BigDecimal latitude, int radiusMeters) {
        GeoCandidateQuery.BoundingBox box = GeoCandidateQuery.boundingBox(longitude, latitude, radiusMeters);
        List<Place> raw = placeMapper.selectInBoundingBox(box.minLat(), box.maxLat(), box.minLng(), box.maxLng());
        List<GeoCandidateQuery.PlaceDistance> filtered = GeoCandidateQuery.filterWithinMeters(
                raw,
                longitude.doubleValue(),
                latitude.doubleValue(),
                radiusMeters,
                null);
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

    private void applyLiked(PlaceDetailResponse detail, Long userId) {
        if (detail.getLatestPosts() == null) {
            return;
        }
        for (PostListItemResponse post : detail.getLatestPosts()) {
            post.setLiked(postLikeMapper.exists(post.getId(), userId) > 0);
        }
    }
}
