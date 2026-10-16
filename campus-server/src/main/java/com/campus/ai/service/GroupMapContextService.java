package com.campus.ai.service;

import com.campus.ai.dto.GroupMapSummary;
import com.campus.ai.dto.GroupPlaceContext;
import com.campus.ai.plan.PlanSource;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.util.SqlLikeUtils;
import com.campus.map.dto.MapListItemResponse;
import com.campus.map.dto.MapPlaceSearchItem;
import com.campus.map.entity.SharedMap;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.map.mapper.MapPlaceMapper;
import com.campus.map.mapper.SharedMapMapper;
import com.campus.place.entity.Place;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0.5 群体共建地图上下文：Agent 的空间记忆来源。
 *
 * 群语境只允许看到"当前群关联的共享地图"（1:1 关系，接口仍按列表建模为将来放开多图预留）；
 * 个人语境看到"我加入的地图"。成员校验直接复用 map_member 数据，无任何旁路。
 * 全部数据来自 mapper 参数化查询，不做字符串拼装 SQL。
 */
@Service
public class GroupMapContextService {

    private static final int DEFAULT_SEARCH_LIMIT = 10;
    private static final int MAX_SEARCH_LIMIT = 20;
    private static final int MAX_FINGERPRINT_MAPS = 8;

    private final SharedMapMapper sharedMapMapper;
    private final MapMemberMapper mapMemberMapper;
    private final MapPlaceMapper mapPlaceMapper;
    private final com.campus.map.mapper.PlaceCommentMapper placeCommentMapper;

    public GroupMapContextService(SharedMapMapper sharedMapMapper,
                                  MapMemberMapper mapMemberMapper,
                                  MapPlaceMapper mapPlaceMapper,
                                  com.campus.map.mapper.PlaceCommentMapper placeCommentMapper) {
        this.sharedMapMapper = sharedMapMapper;
        this.mapMemberMapper = mapMemberMapper;
        this.mapPlaceMapper = mapPlaceMapper;
        this.placeCommentMapper = placeCommentMapper;
    }

    /** 群语境：当前群关联的共建地图（要求发起人仍是该地图成员，非成员返回空列表） */
    public Map<String, Object> groupMapsContext(long viewerId, long groupId) {
        List<GroupMapSummary> maps = new ArrayList<>();
        SharedMap map = sharedMapMapper.selectByGroupId(groupId);
        if (map != null && mapMemberMapper.selectByMapAndUser(map.getId(), viewerId) != null) {
            maps.add(toSummary(map, PlanSource.GROUP_MAP));
        }
        return sourceResult(PlanSource.GROUP_MAP, maps);
    }

    /** 群语境的授权地图集合（供检索/上下文/待存校验共用） */
    public List<Long> groupMapIds(long viewerId, long groupId) {
        SharedMap map = sharedMapMapper.selectByGroupId(groupId);
        if (map == null || mapMemberMapper.selectByMapAndUser(map.getId(), viewerId) == null) {
            return List.of();
        }
        return List.of(map.getId());
    }

    /** 个人语境的授权地图集合：当前用户加入的所有共享地图 */
    public List<Long> authorizedMapIds(long userId) {
        return sharedMapMapper.selectByUserId(userId).stream()
                .map(MapListItemResponse::getId)
                .toList();
    }

    /** 个人语境：当前用户加入的全部共享地图 */
    public Map<String, Object> personalMapsContext(long userId) {
        List<MapListItemResponse> mine = sharedMapMapper.selectByUserId(userId);
        List<GroupMapSummary> maps = new ArrayList<>();
        for (MapListItemResponse item : mine) {
            GroupMapSummary summary = new GroupMapSummary();
            summary.setMapId(item.getId());
            summary.setGroupId(groupOf(item.getId()));
            summary.setName(item.getName());
            summary.setDescription(item.getDescription());
            summary.setPlaceCount(item.getPlaceCount());
            summary.setSource(PlanSource.PERSONAL_MAP);
            summary.setUpdatedAt(item.getCreatedAt());
            maps.add(summary);
        }
        return sourceResult(PlanSource.PERSONAL_MAP, maps);
    }

    /**
     * 共建地图语义检索：keyword 同时匹配地点名/简介/分类名/该群备注（成员靠备注沉淀的"什么场合适用"）。
     * mapIds 必须是调用端已解析并做成员校验后的授权地图集合；跨图同地聚合并保留 mapIds 对应关系。
     */
    public List<GroupPlaceContext> searchPlaces(List<Long> mapIds, String keyword, Long categoryId,
                                                Integer limit, String source) {
        List<GroupPlaceContext> primary = searchByPattern(mapIds, keyword, categoryId, limit, source);
        if (!primary.isEmpty()) {
            return primary;
        }
        // 中文整词子串经常过长（“小组讨论”未必是备注原文连续子串），0 命中且关键词 ≥4 字时
        // 自动用头/尾两字短词回退检索（“小组”、“讨论”），仍保持参数绑定与结果聚合
        String trimmed = keyword.trim();
        if (trimmed.length() >= 4) {
            java.util.LinkedHashMap<Long, GroupPlaceContext> merged = new java.util.LinkedHashMap<>();
            for (String token : java.util.List.of(trimmed.substring(0, 2), trimmed.substring(trimmed.length() - 2))) {
                for (GroupPlaceContext context : searchByPattern(mapIds, token, categoryId, limit, source)) {
                    merged.putIfAbsent(context.getPlaceId(), context);
                }
            }
            return List.copyOf(merged.values()).subList(0, Math.min(merged.size(), 20));
        }
        return List.of();
    }

    private List<GroupPlaceContext> searchByPattern(List<Long> mapIds, String keyword, Long categoryId,
                                                    Integer limit, String source) {
        if (mapIds == null || mapIds.isEmpty()) {
            return List.of();
        }
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        String pattern = SqlLikeUtils.escapeLike(keyword.trim());
        int effectiveLimit = limit == null ? DEFAULT_SEARCH_LIMIT : Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT);
        List<MapPlaceSearchItem> rows = mapPlaceMapper.searchPlacesOnMaps(
                mapIds, pattern, categoryId, effectiveLimit);
        return aggregate(rows, source);
    }

    /** 指定地点的完整上下文：仅当地点落在授权地图集合内时返回（不在则 404，防止越权读备注） */
    public GroupPlaceContext placeContext(List<Long> mapIds, Long placeId, String source) {
        if (mapIds == null || mapIds.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "该地点不在你可访问的共建地图上");
        }
        List<MapPlaceSearchItem> rows = mapPlaceMapper.selectPlacesByMapAndPlaceIds(mapIds, List.of(placeId));
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "该地点不在你可访问的共建地图上");
        }
        List<GroupPlaceContext> list = aggregate(rows, source);
        return list.get(0);
    }

    /** 授权字段：地点是否与目标地图集合有关联行（提交前二次校验复用） */
    public boolean placesExistOnMaps(List<Long> mapIds, List<Long> placeIds) {
        if (mapIds == null || mapIds.isEmpty() || placeIds == null || placeIds.isEmpty()) {
            return false;
        }
        return !mapPlaceMapper.selectPlacesByMapAndPlaceIds(
                mapIds.stream().distinct().toList(),
                placeIds.stream().distinct().toList()).isEmpty();
    }

    /** P1.5 plan_itinerary：授权地图范围内按 placeId 取候选地点行（缺失的 placeId 由调用端拒绝） */
    public List<MapPlaceSearchItem> placesInMaps(List<Long> mapIds, List<Long> placeIds) {
        if (mapIds == null || mapIds.isEmpty() || placeIds == null || placeIds.isEmpty()) {
            return List.of();
        }
        return mapPlaceMapper.selectPlacesByMapAndPlaceIds(
                mapIds.stream().distinct().toList(),
                placeIds.stream().distinct().toList());
    }

    /**
     * planningSourceVersion：对授权地图集合和其上地点集合的指纹（id+updated_at）。
     * 保存前重算并与草稿中保存值比对，任何地图/地点增删改都会使指纹失效 → 要求重新确认。
     */
    public String fingerprint(List<Long> mapIds) {
        if (mapIds == null || mapIds.isEmpty()) {
            return "";
        }
        List<Long> sorted = mapIds.stream().distinct().sorted().toList();
        if (sorted.size() > MAX_FINGERPRINT_MAPS) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "方案关联地图数量超出限制");
        }
        // 备注聚合（评审项：指纹必须覆盖群内备注变化；place_comment 无 updated_at，用最新 id + 条数）
        Map<Long, String> commentDigestByMap = new LinkedHashMap<>();
        for (Map<String, Object> row : placeCommentMapper.selectDigestByMaps(sorted)) {
            Long mapId = ((Number) row.get("mapId")).longValue();
            Long placeId = ((Number) row.get("placeId")).longValue();
            Long lastCommentId = ((Number) row.get("lastCommentId")).longValue();
            Long commentCount = ((Number) row.get("commentCount")).longValue();
            commentDigestByMap.merge(mapId, "#" + placeId + ":" + lastCommentId + ":" + commentCount,
                    (a, b) -> a + b);
        }
        StringBuilder source = new StringBuilder();
        for (Long mapId : sorted) {
            SharedMap map = sharedMapMapper.selectById(mapId);
            if (map == null) {
                throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "共建地图不存在或已被删除");
            }
            source.append(mapId).append('@').append(map.getUpdatedAt() == null ? "-" : map.getUpdatedAt());
            List<Place> places = mapPlaceMapper.selectPlacesByMapId(mapId);
            for (Place place : places) {
                source.append('|').append(place.getId())
                        .append('@').append(place.getUpdatedAt() == null ? "-" : place.getUpdatedAt());
            }
            String commentDigest = commentDigestByMap.get(mapId);
            if (commentDigest != null) {
                source.append(".c").append(commentDigest);
            }
        }
        return sha256(source.toString());
    }

    private Map<String, Object> sourceResult(String source, List<GroupMapSummary> maps) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("maps", maps);
        return result;
    }

    private GroupMapSummary toSummary(SharedMap map, String source) {
        GroupMapSummary summary = new GroupMapSummary();
        summary.setMapId(map.getId());
        summary.setGroupId(map.getGroupId());
        summary.setName(map.getName());
        summary.setDescription(map.getDescription());
        summary.setPlaceCount(sharedMapMapper.countPlaces(map.getId()));
        summary.setSource(source);
        summary.setUpdatedAt(map.getUpdatedAt());
        return summary;
    }

    private Long groupOf(Long mapId) {
        SharedMap map = sharedMapMapper.selectById(mapId);
        return map == null ? null : map.getGroupId();
    }

    private List<GroupPlaceContext> aggregate(List<MapPlaceSearchItem> rows, String source) {
        Map<Long, GroupPlaceContext> byPlace = new LinkedHashMap<>();
        for (MapPlaceSearchItem row : rows) {
            GroupPlaceContext context = byPlace.computeIfAbsent(row.getId(), id -> toContext(row, source));
            if (!context.getMapIds().contains(row.getMapId())) {
                context.getMapIds().add(row.getMapId());
            }
            if (StringUtils.hasText(row.getNoteDigest())) {
                context.getNotes().add(new GroupPlaceContext.Note(row.getMapId(), row.getNoteDigest()));
            }
        }
        return new ArrayList<>(byPlace.values());
    }

    private GroupPlaceContext toContext(MapPlaceSearchItem row, String source) {
        GroupPlaceContext context = new GroupPlaceContext();
        context.setPlaceId(row.getId());
        context.setName(row.getName());
        context.setDescription(row.getDescription());
        context.setAddress(row.getAddress());
        context.setCategoryId(row.getCategoryId());
        context.setCategoryName(row.getCategoryName());
        context.setLongitude(row.getLongitude() == null ? null : row.getLongitude().doubleValue());
        context.setLatitude(row.getLatitude() == null ? null : row.getLatitude().doubleValue());
        context.setSource(source);
        return context;
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return "fp-" + HexFormat.of().formatHex(bytes).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "指纹计算失败");
        }
    }
}
