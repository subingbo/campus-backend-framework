package com.campus.ai.service;

import com.campus.ai.dto.GeoEvent;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.plan.PlanSource;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 群聊实时输出隐私过滤。
 *
 * <p>群规划卡片已经走 {@code GroupPlanPublicView} 白名单，但 AGENT 文本与
 * {@code AGENT_GEO} 帧此前是工具原始输出。这里做两件事：</p>
 * <ul>
 *   <li>GEO：只保留共建地图/校园库/高德 POI；私有地图、实时起点和独立路线事件整段丢弃；</li>
 *   <li>文本：把方案中来自私人地图的站点名替换为“私人地点”，避免模型总结点名。</li>
 * </ul>
 */
public final class GroupAgentPayloadFilter {

    private static final Set<String> PUBLIC_GEO_SOURCES = Set.of(
            GeoEvent.SOURCE_GROUP_MAP,
            GeoEvent.SOURCE_CAMPUS_LIB,
            GeoEvent.SOURCE_AMAP_POI
    );

    private static final String PRIVATE_PLACE_LABEL = "私人地点";

    private GroupAgentPayloadFilter() {
    }

    public static List<GeoEvent> filterGeoEvents(List<GeoEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<GeoEvent> filtered = new ArrayList<>();
        for (GeoEvent event : events) {
            if (event == null) {
                continue;
            }
            if (GeoEvent.TYPE_PLACES.equals(event.getType())) {
                List<GeoEvent.GeoPlace> places = filterPlaces(event.getPlaces());
                if (!places.isEmpty()) {
                    GeoEvent copy = copyEvent(event);
                    copy.setPlaces(places);
                    filtered.add(copy);
                }
                continue;
            }
            if (GeoEvent.TYPE_ITINERARY.equals(event.getType())) {
                List<GeoEvent.GeoPlace> places = filterPlaces(event.getPlaces());
                // 任一预览站点来自私有地图/实时位置时整段丢弃，避免路线跨隐藏站点
                if (places.size() == event.getPlaces().size() && !places.isEmpty()) {
                    GeoEvent copy = copyEvent(event);
                    copy.setPlaces(places);
                    filtered.add(copy);
                }
                continue;
            }
            // 独立路线事件的端点无法从 GeoEvent 判断归属，群聊一律不广播原始路线
        }
        return filtered;
    }

    public static String filterText(String text, PlanDto plan) {
        if (!StringUtils.hasText(text) || plan == null || plan.getStops() == null) {
            return text;
        }
        String result = text;
        for (PlanStop stop : plan.getStops()) {
            if (stop == null || !isPrivate(stop.getSource())) {
                continue;
            }
            String name = stop.getName();
            if (StringUtils.hasText(name) && name.trim().length() >= 2) {
                result = result.replace(name, PRIVATE_PLACE_LABEL);
            }
        }
        return result;
    }

    private static List<GeoEvent.GeoPlace> filterPlaces(List<GeoEvent.GeoPlace> places) {
        if (places == null || places.isEmpty()) {
            return List.of();
        }
        List<GeoEvent.GeoPlace> allowed = new ArrayList<>();
        for (GeoEvent.GeoPlace place : places) {
            if (place != null && PUBLIC_GEO_SOURCES.contains(place.getSource())) {
                allowed.add(place);
            }
        }
        return allowed;
    }

    private static boolean isPrivate(String source) {
        return PlanSource.PERSONAL_MAP.equals(source) || PlanSource.USER_LOCATION.equals(source);
    }

    private static GeoEvent copyEvent(GeoEvent event) {
        GeoEvent copy = new GeoEvent();
        copy.setType(event.getType());
        copy.setTool(event.getTool());
        copy.setSource(event.getSource());
        copy.setNote(event.getNote());
        copy.setRoute(event.getRoute());
        copy.setSegments(event.getSegments());
        return copy;
    }
}
