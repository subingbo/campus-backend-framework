package com.campus.ai.tool;

import com.campus.ai.dto.AiUserLocation;
import com.campus.ai.dto.GeoEvent;
import com.campus.ai.dto.GroupPlaceContext;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.planner.FreeWindow;
import com.campus.ai.planner.FreeWindowSolver;
import com.campus.ai.planner.PlanConflictAnalyzer;
import com.campus.ai.planner.PlannerModels;
import com.campus.ai.planner.SpatiotemporalPlanner;
import com.campus.ai.plan.PlanSource;
import com.campus.ai.service.GroupMapContextService;
import com.campus.travel.PoiItem;
import com.campus.travel.RouteInfo;
import com.campus.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.util.SqlLikeUtils;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.dto.MapMemberResponse;
import com.campus.map.dto.MapPlaceSearchItem;
import com.campus.map.service.SharedMapService;
import com.campus.place.dto.PlaceBriefResponse;
import com.campus.place.mapper.PlaceMapper;
import com.campus.schedule.dto.BusyEntryDto;
import com.campus.schedule.dto.GroupBusyDto;
import com.campus.schedule.entity.ScheduleSharePermission;
import com.campus.schedule.service.ScheduleService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 只读工具集。所有工具的权限语义与普通用户完全一致（成员校验复用 SharedMapService，
 * 不经任何旁路），工具结果统一序列化为 JSON 字符串回填给大模型。
 * submit_plan 由 AgentService 拦截，不在此调度。
 *
 * 时空协同（P0/P1/P2）：get_my_schedule / find_free_windows 走 ScheduleService 多源忙闲与确定性求解器；
 * 群体语境（向量传递 viewerId+groupId，来自后端会话而非模型）下 find_free_windows 返回群体忙闲与覆盖情况。
 */
@Slf4j
@Component
public class AgentToolkit {

    public static final String TOOL_LIST_MY_MAPS = "list_my_maps";
    public static final String TOOL_LIST_MAP_PLACES = "list_map_places";
    public static final String TOOL_SEARCH_PLACES = "search_places";
    public static final String TOOL_ROUTE_INFO = "route_info";
    public static final String TOOL_AMAP_POI_SEARCH = "amap_poi_search";
    public static final String TOOL_GET_MY_SCHEDULE = "get_my_schedule";
    public static final String TOOL_FIND_FREE_WINDOWS = "find_free_windows";
    public static final String TOOL_GET_GROUP_MAP_CONTEXT = "get_group_map_context";
    public static final String TOOL_SEARCH_GROUP_MAP_PLACES = "search_group_map_places";
    public static final String TOOL_GET_PLACE_CONTEXT = "get_place_context";
    public static final String TOOL_PLAN_ITINERARY = "plan_itinerary";
    public static final String TOOL_GET_MY_LOCATION = "get_my_location";

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SharedMapService sharedMapService;
    private final PlaceMapper placeMapper;
    private final TravelInfoProvider travelInfoProvider;
    private final ObjectMapper objectMapper;
    private final ScheduleService scheduleService;
    private final GroupMapContextService groupMapContextService;

    /** 评测消融开关；默认 none，生产行为不变 */
    @org.springframework.beans.factory.annotation.Value("${campus.ai.eval.ablation:none}")
    private String ablation = "none";

    /** 群语境（由后端从 ws 会话注入，模型不可指定）：key1=viewerId key2=campus_group.id */
    private static final ThreadLocal<long[]> GROUP_CONTEXT = new ThreadLocal<>();

    public static void setGroupContext(long viewerId, long groupId) {
        GROUP_CONTEXT.set(new long[]{viewerId, groupId});
    }

    public static void clearGroupContext() {
        GROUP_CONTEXT.remove();
    }

    public boolean hasGroupContext() {
        long[] ctx = GROUP_CONTEXT.get();
        return ctx != null && ctx[0] > 0 && ctx[1] > 0;
    }

    /** 本轮对话的用户实时定位（客户端随请求上送、AgentService 注入，模型不可指定） */
    private static final ThreadLocal<AiUserLocation> RUN_LOCATION = new ThreadLocal<>();

    public static void setRunLocation(AiUserLocation location) {
        RUN_LOCATION.set(location);
    }

    /** 本轮注入的实时定位；仅供提交盖章核对起点坐标，不对外暴露存储 */
    public static AiUserLocation currentRunLocation() {
        return RUN_LOCATION.get();
    }

    public static void clearRunLocation() {
        RUN_LOCATION.remove();
    }

    public boolean hasRunLocation() {
        AiUserLocation loc = RUN_LOCATION.get();
        return loc != null && loc.getLongitude() != null && loc.getLatitude() != null;
    }

    // ---------- 本轮地理事件收集（前端实时上图 + 助手消息落库回放） ----------

    /** 折线抽稀上限：超过按步长采样，避免 geo_json 体积膨胀 */
    private static final int MAX_POLYLINE_POINTS = 200;
    /** 单轮事件上限（防御性）：超出后不再收集，仅影响地图丰富度 */
    private static final int MAX_GEO_EVENTS = 60;

    private static final ThreadLocal<List<GeoEvent>> RUN_GEO = new ThreadLocal<>();

    public static void setRunGeoCollector() {
        RUN_GEO.set(new ArrayList<>());
    }

    public static void clearRunGeoCollector() {
        RUN_GEO.remove();
    }

    /** 由 AgentService 在每个工具执行后 / 最终答复前 drain；无收集器时返回空列表 */
    public static List<GeoEvent> drainRunGeo() {
        List<GeoEvent> bucket = RUN_GEO.get();
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }
        List<GeoEvent> drained = new ArrayList<>(bucket);
        bucket.clear();
        return drained;
    }

    private void collect(GeoEvent event) {
        List<GeoEvent> bucket = RUN_GEO.get();
        if (bucket == null || bucket.size() >= MAX_GEO_EVENTS) {
            return;
        }
        bucket.add(event);
    }

    private static List<double[]> thin(List<double[]> polyline) {
        if (polyline == null || polyline.isEmpty()) {
            return new ArrayList<>();
        }
        if (polyline.size() <= MAX_POLYLINE_POINTS) {
            return new ArrayList<>(polyline);
        }
        int step = (int) Math.ceil(polyline.size() / (double) MAX_POLYLINE_POINTS);
        List<double[]> sampled = new ArrayList<>();
        for (int i = 0; i < polyline.size(); i += step) {
            sampled.add(polyline.get(i));
        }
        double[] last = polyline.get(polyline.size() - 1);
        if (sampled.isEmpty() || sampled.get(sampled.size() - 1) != last) {
            sampled.add(last);
        }
        return sampled;
    }

    private GeoEvent placesEvent(String tool, String source, List<GeoEvent.GeoPlace> places, String note) {
        GeoEvent event = new GeoEvent();
        event.setType(GeoEvent.TYPE_PLACES);
        event.setTool(tool);
        event.setSource(source);
        event.setPlaces(places);
        event.setNote(note);
        return event;
    }

    private GeoEvent.GeoPlace toGeoPlace(GroupPlaceContext context, String source) {
        GeoEvent.GeoPlace place = new GeoEvent.GeoPlace();
        place.setPlaceId(context.getPlaceId());
        place.setName(context.getName());
        place.setAddress(context.getAddress());
        place.setCategoryName(context.getCategoryName());
        place.setLongitude(context.getLongitude());
        place.setLatitude(context.getLatitude());
        place.setMapIds(context.getMapIds());
        place.setSource(source);
        if (context.getNotes() != null && !context.getNotes().isEmpty()
                && context.getNotes().get(0).getContent() != null) {
            String digest = context.getNotes().get(0).getContent().trim();
            place.setNoteDigest(digest.length() > 160 ? digest.substring(0, 160) : digest);
        }
        return place;
    }

    public AgentToolkit(SharedMapService sharedMapService,
                        PlaceMapper placeMapper,
                        TravelInfoProvider travelInfoProvider,
                        @Qualifier("redisObjectMapper") ObjectMapper objectMapper,
                        ScheduleService scheduleService,
                        GroupMapContextService groupMapContextService) {
        this.sharedMapService = sharedMapService;
        this.placeMapper = placeMapper;
        this.travelInfoProvider = travelInfoProvider;
        this.objectMapper = objectMapper;
        this.scheduleService = scheduleService;
        this.groupMapContextService = groupMapContextService;
    }

    /** OpenAI function calling 的 tools 参数 */
    public List<Map<String, Object>> schemas() {
        List<Map<String, Object>> functions = new ArrayList<>();
        functions.add(function(TOOL_LIST_MY_MAPS,
                "获取我加入的共享地图列表。规划前优先调用，了解有哪些用户共建的地图可用",
                Map.of(), List.of()));
        functions.add(function(TOOL_LIST_MAP_PLACES,
                "查看指定共享地图内的全部地点（名称/分类/经纬度 GCJ-02）。mapId 必须来自 list_my_maps 的结果",
                props(Map.of("mapId", type("integer", "共享地图ID"))), List.of("mapId")));
        functions.add(function(TOOL_SEARCH_PLACES,
                "在系统校园地点库按名称/描述/分类模糊搜索",
                props(Map.of("keyword", type("string", "搜索关键字"))), List.of("keyword")));
        functions.add(function(TOOL_ROUTE_INFO,
                "查询两点间步行/骑行/驾车路线的预估距离与耗时（coordinates GCJ-02）。站点时间必须依据此耗时推算",
                props(Map.of(
                        "fromLng", type("number", "起点经度"),
                        "fromLat", type("number", "起点纬度"),
                        "toLng", type("number", "终点经度"),
                        "toLat", type("number", "终点纬度"),
                        "mode", enumType("交通方式", "WALK", "RIDE", "DRIVE"))),
                List.of("fromLng", "fromLat", "toLng", "toLat", "mode")));
        functions.add(function(TOOL_AMAP_POI_SEARCH,
                "检索高德 POI（系统地图里没有的校外/新地点），返回名称/地址/坐标（GCJ-02）；无结果返回空列表",
                props(Map.of(
                        "keyword", type("string", "POI 关键字"),
                        "nearLng", type("number", "可选：附近中心点经度"),
                        "nearLat", type("number", "可选：附近中心点纬度"),
                        "radiusMeters", type("integer", "可选：检索半径米数"))),
                List.of("keyword")));
        functions.add(function(TOOL_GET_MY_SCHEDULE,
                "查询我的忙闲：个人日程与已报名/已创建活动的合并占用时间（SCHEDULE/ACTIVITY 来源；含标题，仅本人可见）",
                props(Map.of(
                        "dateFrom", type("string", "窗口开始 yyyy-MM-ddTHH:mm"),
                        "dateTo", type("string", "窗口结束 yyyy-MM-ddTHH:mm"))),
                List.of("dateFrom", "dateTo")));
        functions.add(function(TOOL_FIND_FREE_WINDOWS,
                "查找可用的连续空闲窗口（存在人多场合的群体语境时返回覆盖情况；未授权成员不会计入忙闲）",
                props(Map.of(
                        "dateFrom", type("string", "窗口开始 yyyy-MM-ddTHH:mm"),
                        "dateTo", type("string", "窗口结束 yyyy-MM-ddTHH:mm"),
                        "durationMinutes", type("integer", "需要的连续分钟数（15～600）"))),
                List.of("dateFrom", "dateTo", "durationMinutes")));
        functions.add(function(TOOL_GET_MY_LOCATION,
                "获取用户当前的实时位置（GCJ-02 坐标，来自设备定位）。涉及“我现在在哪 / 从哪出发 / 到某地去”的路线、耗时与交通方式问题时先调用，"
                        + "坐标可直接用于 route_info / plan_itinerary；若返回 available:false，请提醒用户在输入框旁打开「📍 位置」开关后重新发送，不要臆测出发点",
                Map.of(), List.of()));
        functions.add(function(TOOL_GET_GROUP_MAP_CONTEXT,
                "获取当前语境可用的共建地图清单（群聊里=本群共享地图；个人会话=我加入的地图），含地图名称/描述/地点数。群体规划的第一步",
                Map.of(), List.of()));
        functions.add(function(TOOL_SEARCH_GROUP_MAP_PLACES,
                "在共建地图内按语义检索地点：地点名/简介/分类/成员备注四路匹配（例如备注“适合讨论”就能命中）。返回源标签 GROUP_MAP/PERSONAL_MAP 与各图备注原文",
                props(Map.of(
                        "keyword", type("string", "搜索关键字（可用活动意图，如“适合小组讨论”“带电脑学习”）"),
                        "categoryId", type("integer", "可选：地点分类ID 精确过滤"),
                        "limit", type("integer", "可选：返回上限，默认 10，最大 20"))),
                List.of("keyword")));
        functions.add(function(TOOL_GET_PLACE_CONTEXT,
                "读取一个共建地图地点的完整上下文：名称/简介/坐标/地址/分类/各张图下的成员备注原文。placeId 必须在当前授权地图范围内",
                props(Map.of(
                        "placeId", type("integer", "系统地点ID"),
                        "mapId", type("integer", "可选：仅查看该地图下的投影"))),
                List.of("placeId")));
        Map<String, Object> itineraryProps = new LinkedHashMap<>();
        itineraryProps.put("dateFrom", type("string", "窗口开始 yyyy-MM-ddTHH:mm"));
        itineraryProps.put("dateTo", type("string", "窗口结束 yyyy-MM-ddTHH:mm"));
        itineraryProps.put("placeIds", Map.of("type", "array", "items", Map.of("type", "integer"),
                "description", "候选地点（≤4 个，来自 search_group_map_places / list_map_places）"));
        itineraryProps.put("keyword", type("string", "可选：共建地图语义检索，自动取最相关 ≤4 个地点"));
        itineraryProps.put("stayMinutes", Map.of("type", "array", "items", Map.of("type", "integer"),
                "description", "可选：与最终候选一一对应的停留分钟数（缺省 60）"));
        itineraryProps.put("transportMode", enumType("出行方式（公交为估算耗时）",
                "WALK", "RIDE", "DRIVE", "TAXI", "TRANSIT"));
        itineraryProps.put("bufferMinutes", type("integer", "可选：最后一站后保留缓冲分钟数（缺省 10）"));
        itineraryProps.put("originLng", type("number", "可选：往返行程起点 GCJ-02 经度（通常取 get_my_location 的坐标）"));
        itineraryProps.put("originLat", type("number", "可选：往返行程起点 GCJ-02 纬度"));
        itineraryProps.put("originName", type("string", "可选：起点名称（缺省“出发点”）"));
        itineraryProps.put("returnToStart", type("boolean", "可选：true 时末尾追加返回起点站（需同时给 originLng/originLat）"));
        functions.add(function(TOOL_PLAN_ITINERARY,
                "确定性时空联合求解：给日期窗口与候选地点（placeIds 或 keyword），由系统计算共同空闲、真实交通耗时与站点顺序，"
                        + "返回最多 3 个带回精确起止时间的可行方案；无解时返回原因与建议。方案时间必须原样引用，不得自行编造或调整。"
                        + "往返行程（从我的位置出发并回到原点）必须传 originLng/originLat 与 returnToStart=true，"
                        + "输出的 stops 已含起点与返程站，直接原样提交，不要自己补站",
                itineraryProps,
                List.of("dateFrom", "dateTo")));
        Map<String, Object> stopItemProps = new LinkedHashMap<>();
        stopItemProps.put("name", type("string", "站点名称"));
        stopItemProps.put("placeId", type("integer", "系统地点ID（有则优先）"));
        stopItemProps.put("longitude", type("number", "GCJ-02 经度"));
        stopItemProps.put("latitude", type("number", "GCJ-02 纬度"));
        stopItemProps.put("startTime", type("string", "到达该站的绝对时间（前一站 endTime + 交通耗时，严格按计划引用）"));
        stopItemProps.put("endTime", type("string", "离开该站的绝对时间（startTime + 停留；须与下一站 startTime 留出交通时间）"));
        stopItemProps.put("transportMode", enumType("前往本站的交通方式；留空按步行（WALK）校验",
                "WALK", "RIDE", "DRIVE", "TAXI", "TRANSIT"));
        stopItemProps.put("stayMinutes", type("integer", "可选：该站停留分钟数（来自 plan_itinerary）"));
        stopItemProps.put("note", type("string", "站点备注，256 字内"));
        Map<String, Object> stopsSchema = new LinkedHashMap<>();
        stopsSchema.put("type", "array");
        stopsSchema.put("items", Map.of("type", "object", "properties", stopItemProps));

        Map<String, Object> coverageItemProps = new LinkedHashMap<>();
        coverageItemProps.put("userId", type("integer", "成员用户ID"));
        coverageItemProps.put("status", enumType("忙闲覆盖状态", "KNOWN", "UNKNOWN"));
        Map<String, Object> coverageSchema = new LinkedHashMap<>();
        coverageSchema.put("type", "array");
        coverageSchema.put("items", Map.of("type", "object", "properties", coverageItemProps));

        Map<String, Object> segmentItemProps = new LinkedHashMap<>();
        segmentItemProps.put("fromSeq", type("integer", "起始站点序号（1-based）"));
        segmentItemProps.put("toSeq", type("integer", "目的站点序号（1-based）"));
        segmentItemProps.put("transportMode", enumType("该段交通方式", "WALK", "RIDE", "DRIVE", "TRANSIT"));
        segmentItemProps.put("distanceMeters", type("number", "该段距离（米）"));
        segmentItemProps.put("durationMinutes", type("integer", "该段耗时（分钟）"));
        Map<String, Object> segmentsSchema = new LinkedHashMap<>();
        segmentsSchema.put("type", "array");
        segmentsSchema.put("items", Map.of("type", "object", "properties", segmentItemProps));

        Map<String, Object> submitPlanProps = new LinkedHashMap<>();
        submitPlanProps.put("title", type("string", "活动标题，128 字内"));
        submitPlanProps.put("category", enumType("活动类型", "MEETING", "COMPETITION", "OTHER"));
        submitPlanProps.put("summary", type("string", "一段话概述本次安排"));
        submitPlanProps.put("startTime", type("string", "活动开始时间 yyyy-MM-ddTHH:mm:ss"));
        submitPlanProps.put("endTime", type("string", "活动结束时间 yyyy-MM-ddTHH:mm:ss"));
        submitPlanProps.put("maxMembers", type("integer", "人数上限，缺省 20"));
        submitPlanProps.put("referenceMapId", type("integer", "可选：参考的共享地图ID"));
        submitPlanProps.put("stops", stopsSchema);
        submitPlanProps.put("availabilityCoverage", coverageSchema);
        submitPlanProps.put("routeSegments", segmentsSchema);
        submitPlanProps.put("totalTravelMinutes", type("integer",
                "无需填写：后端会按真实路线重算并覆写（填了也会被覆盖）"));
        submitPlanProps.put("constraintStatus", enumType("可行性状态（存在未授权忙闲成员时为 TENTATIVE）",
                "FEASIBLE", "TENTATIVE"));
        submitPlanProps.put("reason", type("string", "可选：推荐理由（引用共建地点备注等真实依据，500 字内）"));
        functions.add(function("submit_plan",
                "规划完成后调用：提交结构化活动草稿。startTime/endTime 为活动整体起止（绝对时间）；stops 按时间顺序排列，"
                        + "其中 placeId（系统地点）与 longitude/latitude（GCJ-02）二选一。"
                        + "若时间线来自 plan_itinerary，须原样引用其 stops/routeSegments/totalTravelMinutes/availabilityCoverage 数据",
                props(submitPlanProps),
                List.of("title", "startTime", "endTime", "stops")));
        return functions.stream()
                .filter(fn -> toolEnabled(((Map<?, ?>) fn).get("name").toString()))
                .map(fn -> Map.<String, Object>of("type", "function", "function", fn))
                .toList();
    }

    /** 评测消融：只影响 schema/执行入口，不关闭 PlanValidator 等硬校验。 */
    private boolean toolEnabled(String name) {
        if ("no-planner".equalsIgnoreCase(ablation)) {
            return !TOOL_PLAN_ITINERARY.equals(name);
        }
        if ("no-group-map".equalsIgnoreCase(ablation)) {
            return !TOOL_GET_GROUP_MAP_CONTEXT.equals(name)
                    && !TOOL_SEARCH_GROUP_MAP_PLACES.equals(name)
                    && !TOOL_GET_PLACE_CONTEXT.equals(name)
                    && !TOOL_LIST_MY_MAPS.equals(name)
                    && !TOOL_LIST_MAP_PLACES.equals(name);
        }
        return true;
    }

    /** 执行工具并回填 JSON；权限一律走业务层校验，异常转为 error JSON 让模型自行纠正 */
    public String execute(String name, String argsJson) {
        if (!toolEnabled(name)) {
            return errorJson("当前评测消融模式已禁用工具：" + name);
        }
        try {
            Map<String, Object> args = objectMapper.readValue(argsJson, new TypeReference<>() {
            });
            return switch (name) {
                case TOOL_LIST_MY_MAPS -> jsonify(sharedMapService.listMyMaps());
                case TOOL_LIST_MAP_PLACES -> jsonify(sharedMapService.listMapPlaces(
                        requiredLong(args, "mapId")));
                case TOOL_SEARCH_PLACES -> {
                    List<PlaceBriefResponse> found = placeMapper.searchPlaces(
                            SqlLikeUtils.escapeLike(requiredText(args, "keyword")), 10);
                    List<GeoEvent.GeoPlace> geoPlaces = new ArrayList<>();
                    for (PlaceBriefResponse item : found) {
                        GeoEvent.GeoPlace place = new GeoEvent.GeoPlace();
                        place.setPlaceId(item.getId());
                        place.setName(item.getName());
                        place.setCategoryName(item.getCategoryName());
                        place.setLongitude(item.getLongitude() == null ? null : item.getLongitude().doubleValue());
                        place.setLatitude(item.getLatitude() == null ? null : item.getLatitude().doubleValue());
                        place.setSource(GeoEvent.SOURCE_CAMPUS_LIB);
                        geoPlaces.add(place);
                    }
                    collect(placesEvent(TOOL_SEARCH_PLACES, GeoEvent.SOURCE_CAMPUS_LIB, geoPlaces, null));
                    yield jsonify(found);
                }
                case TOOL_GET_GROUP_MAP_CONTEXT -> jsonify(groupMapContextTool());
                case TOOL_SEARCH_GROUP_MAP_PLACES -> jsonify(searchGroupMapPlacesTool(args));
                case TOOL_GET_PLACE_CONTEXT -> jsonify(placeContextTool(args));
                case TOOL_PLAN_ITINERARY -> jsonify(planItineraryTool(args));
                case TOOL_GET_MY_SCHEDULE -> jsonify(myScheduleTool(args));
                case TOOL_FIND_FREE_WINDOWS -> jsonify(findFreeWindowsTool(args, hasGroupContext()));
                case TOOL_GET_MY_LOCATION -> jsonify(myLocationTool());
                case TOOL_ROUTE_INFO -> {
                    String routeMode = requiredText(args, "mode");
                    double fromLng = requiredNumber(args, "fromLng");
                    double fromLat = requiredNumber(args, "fromLat");
                    double toLng = requiredNumber(args, "toLng");
                    double toLat = requiredNumber(args, "toLat");
                    RouteInfo route = travelInfoProvider.searchRoute(routeMode, fromLng, fromLat, toLng, toLat);
                    Map<String, Object> compact = new LinkedHashMap<>();
                    compact.put("mode", route.getMode());
                    compact.put("distanceMeters", route.getDistanceMeters());
                    compact.put("durationSeconds", route.getDurationSeconds());
                    compact.put("durationMinutes", route.getDurationSeconds() == null
                            ? null
                            : Math.round(route.getDurationSeconds() / 6.0) / 10.0);
                    compact.put("estimated", route.isEstimated());
                    compact.put("provider", route.getProvider());
                    // 给模型的是 compact 字段；完整折线只进地理事件（前端上图）
                    GeoEvent routeEvent = new GeoEvent();
                    routeEvent.setType(GeoEvent.TYPE_ROUTE);
                    routeEvent.setTool(TOOL_ROUTE_INFO);
                    routeEvent.setSource(GeoEvent.SOURCE_ROUTE);
                    GeoEvent.GeoRoute geoRoute = new GeoEvent.GeoRoute();
                    geoRoute.setMode(route.getMode());
                    geoRoute.setDistanceMeters(route.getDistanceMeters());
                    geoRoute.setDurationSeconds(route.getDurationSeconds());
                    geoRoute.setEstimated(route.isEstimated());
                    geoRoute.setProvider(route.getProvider());
                    geoRoute.setPolyline(thin(route.getPolyline()));
                    geoRoute.setFrom(new double[]{fromLng, fromLat});
                    geoRoute.setTo(new double[]{toLng, toLat});
                    routeEvent.setRoute(geoRoute);
                    collect(routeEvent);
                    yield jsonify(compact);
                }
                case TOOL_AMAP_POI_SEARCH -> {
                    List<PoiItem> pois = travelInfoProvider.searchPoi(
                            requiredText(args, "keyword"),
                            optionalNumber(args, "nearLng"),
                            optionalNumber(args, "nearLat"),
                            optionalInt(args, "radiusMeters"));
                    List<GeoEvent.GeoPlace> poiPlaces = new ArrayList<>();
                    for (PoiItem poi : pois) {
                        GeoEvent.GeoPlace place = new GeoEvent.GeoPlace();
                        place.setName(poi.getName());
                        place.setAddress(poi.getAddress());
                        place.setLongitude(poi.getLongitude());
                        place.setLatitude(poi.getLatitude());
                        place.setDistanceMeters(poi.getDistanceMeters());
                        place.setSource(GeoEvent.SOURCE_AMAP_POI);
                        poiPlaces.add(place);
                    }
                    collect(placesEvent(TOOL_AMAP_POI_SEARCH, GeoEvent.SOURCE_AMAP_POI, poiPlaces, null));
                    yield jsonify(pois);
                }
                default -> errorJson("未知工具：" + name);
            };
        } catch (BusinessException ex) {
            return errorJson(ex.getMessage());
        } catch (Exception ex) {
            log.warn("工具 {} 执行异常：{}", name, ex.toString());
            return errorJson("工具执行异常，请稍后重试");
        }
    }

    private String requiredText(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new BusinessException(com.campus.common.result.ResultCode.BAD_REQUEST.getCode(),
                    "参数 " + key + " 不能为空");
        }
        return value.toString();
    }

    private long requiredLong(Map<String, Object> args, String key) {
        return asNumber(args, key).longValue();
    }

    private double requiredNumber(Map<String, Object> args, String key) {
        return asNumber(args, key).doubleValue();
    }

    private Double optionalNumber(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : asNumber(args, key).doubleValue();
    }

    private Integer optionalInt(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : (int) asNumber(args, key).doubleValue();
    }

    /** 工具参数数字：JSON number 与数字字符串都接受，其余明确报错，让模型自行纠正 */
    private Number asNumber(Map<String, Object> args, String key) {
        Object value = optionalOrThrow(args, key);
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                // 统一按非法参数处理
            }
        }
        throw new BusinessException(com.campus.common.result.ResultCode.BAD_REQUEST.getCode(),
                "参数 " + key + " 必须为数字");
    }

    private Object optionalOrThrow(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            throw new BusinessException(com.campus.common.result.ResultCode.BAD_REQUEST.getCode(),
                    "参数 " + key + " 不能为空");
        }
        return value;
    }

    /** 日期时间窗口参数：yyyy-MM-ddTHH:mm（可带秒） */
    private LocalDateTime windowArg(Map<String, Object> args, String key) {
        String value = requiredText(args, key);
        try {
            return LocalDateTime.parse(value.trim(), TS);
        } catch (DateTimeParseException ex) {
            throw new BusinessException(com.campus.common.result.ResultCode.BAD_REQUEST.getCode(),
                    "参数 " + key + " 需为 yyyy-MM-ddTHH:mm 格式");
        }
    }

    private List<BusyEntryDto> ownBusy(Map<String, Object> args) {
        LocalDateTime from = windowArg(args, "dateFrom");
        LocalDateTime to = windowArg(args, "dateTo");
        return scheduleService.myBusy(viewerId(), from, to);
    }

    /**
     * 当前语境的用户：群语境=后端注入的发起人（agent-group 线程没有 SecurityContext，
     * get_my_schedule/plan_itinerary 不能用 SecurityUtils，否则报“未登录”）；个人语境=当前登录用户。
     */
    private long viewerId() {
        if (hasGroupContext()) {
            long[] ctx = GROUP_CONTEXT.get();
            return ctx[0];
        }
        return com.campus.security.user.SecurityUtils.getUserId();
    }

    private Map<String, Object> myScheduleTool(Map<String, Object> args) {
        List<BusyEntryDto> busy = ownBusy(args);
        boolean truncated = busy.size() > 30;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (BusyEntryDto entry : busy.subList(0, Math.min(busy.size(), 30))) {
            entries.add(compactBusy(entry, true));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        result.put("truncated", truncated);
        return result;
    }

    /** 用户实时定位：客户端随请求上送、本轮线程注入；未提供时如实告知，模型不应编造起点 */
    private Map<String, Object> myLocationTool() {
        Map<String, Object> result = new LinkedHashMap<>();
        AiUserLocation location = RUN_LOCATION.get();
        if (location == null || location.getLongitude() == null || location.getLatitude() == null) {
            result.put("available", false);
            result.put("hint", "本轮对话未携带实时定位；请用户点击输入框旁「📍 位置」开关后重新发送");
            return result;
        }
        result.put("available", true);
        result.put("longitude", location.getLongitude());
        result.put("latitude", location.getLatitude());
        result.put("accuracyMeters", location.getAccuracy());
        result.put("capturedAt", location.getCapturedAt());
        result.put("source", location.getSource() == null ? "device" : location.getSource());
        result.put("coordinateSystem", "GCJ-02");
        result.put("note", "发送前一刻从取样设备获取的快照；精度有限时按大范围推荐，勿当作精确到门口的位置");
        // 位置同步进地理事件：前端画"我的位置"蓝点并可回放
        GeoEvent locatedEvent = new GeoEvent();
        locatedEvent.setType(GeoEvent.TYPE_USER_LOCATION);
        locatedEvent.setTool(TOOL_GET_MY_LOCATION);
        locatedEvent.setSource(GeoEvent.SOURCE_USER_LOCATION);
        GeoEvent.GeoPlace me = new GeoEvent.GeoPlace();
        me.setName("我的位置");
        me.setLongitude(location.getLongitude());
        me.setLatitude(location.getLatitude());
        me.setDistanceMeters(location.getAccuracy());
        me.setSource(GeoEvent.SOURCE_USER_LOCATION);
        locatedEvent.setPlaces(List.of(me));
        locatedEvent.setNote("manual".equals(location.getSource()) ? "用户手动纠正的位置" : "设备定位位置");
        collect(locatedEvent);
        return result;
    }

    private Map<String, Object> findFreeWindowsTool(Map<String, Object> args, boolean groupContext) {
        LocalDateTime from = windowArg(args, "dateFrom");
        LocalDateTime to = windowArg(args, "dateTo");
        long minutes = requiredLong(args, "durationMinutes");
        if (minutes < 15 || minutes > 600) {
            throw new BusinessException(com.campus.common.result.ResultCode.BAD_REQUEST.getCode(),
                    "durationMinutes 须在 15～600");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (!groupContext) {
            List<BusyEntryDto> busy = ownBusy(args);
            List<FreeWindow> free = FreeWindowSolver.findFree(busy, from, to, minutes);
            boolean truncated = free.size() > 12;
            List<Map<String, Object>> windows = new ArrayList<>();
            for (FreeWindow window : free.subList(0, Math.min(free.size(), 12))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("start", TS.format(window.start()));
                row.put("end", TS.format(window.end()));
                row.put("minutes", window.durationMinutes());
                windows.add(row);
            }
            result.put("durationMinutes", minutes);
            result.put("windows", windows);
            result.put("windowsTruncated", truncated);
            result.put("source", "personal");
            return result;
        }
        // 群体语境：忙闲=已授权成员的并集；未授权成员不作为忙碌来源，方案标注暂定
        long[] ctx = GROUP_CONTEXT.get();
        GroupBusyDto groupBusy = scheduleService.groupBusy(ctx[0], ctx[1], from, to);
        List<FreeWindow> free = FreeWindowSolver.findFree(groupBusy.getKnown(), from, to, minutes);
        boolean truncated = free.size() > 12;
        List<Map<String, Object>> windows = new ArrayList<>();
        for (FreeWindow window : free.subList(0, Math.min(free.size(), 12))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("start", TS.format(window.start()));
            row.put("end", TS.format(window.end()));
            row.put("minutes", window.durationMinutes());
            windows.add(row);
        }
        result.put("durationMinutes", minutes);
        result.put("windows", windows);
        result.put("windowsTruncated", truncated);
        // 覆盖情况：已授权者 KNOWN；未授权者 UNKNOWN（不视为有空）
        Map<Long, Boolean> knownByUser = new LinkedHashMap<>();
        for (BusyEntryDto entry : groupBusy.getKnown()) {
            knownByUser.put(entry.getUserId(), Boolean.TRUE);
        }
        List<Map<String, Object>> coverage = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (Long userId : knownByUser.keySet()) {
            seen.add(userId);
            coverage.add(Map.of("userId", userId, "status", "KNOWN"));
        }
        for (Long userId : groupBusy.getUnknownUserIds()) {
            if (!seen.contains(userId)) {
                coverage.add(Map.of("userId", userId, "status", "UNKNOWN"));
            }
        }
        result.put("coverage", coverage);
        result.put("unknownCount", groupBusy.getUnknownUserIds().size());
        result.put("source", "group");
        if (!groupBusy.getUnknownUserIds().isEmpty()) {
            result.put("note", "存在未授权忙闲的成员，方案为暂定，须其确认后方可视为可行");
        }
        return result;
    }

    private Map<String, Object> compactBusy(BusyEntryDto entry, boolean withTitle) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("start", TS.format(entry.getStartTime()));
        row.put("end", TS.format(entry.getEndTime()));
        row.put("source", entry.getSourceType());
        row.put("sourceId", entry.getSourceId());
        if (withTitle && entry.getTitle() != null) {
            row.put("title", entry.getTitle());
        }
        return row;
    }

    // ---------- P0.5 共建地图上下文 ----------

    /** 当前语境的授权地图集合：群语境=本群地图（须为成员）；个人语境=我加入的地图 */
    private List<Long> scopeMapIds() {
        if (hasGroupContext()) {
            long[] ctx = GROUP_CONTEXT.get();
            return groupMapContextService.groupMapIds(ctx[0], ctx[1]);
        }
        return groupMapContextService.authorizedMapIds(
                com.campus.security.user.SecurityUtils.getUserId());
    }

    private String scopeSource() {
        return hasGroupContext() ? PlanSource.GROUP_MAP : PlanSource.PERSONAL_MAP;
    }

    private Map<String, Object> groupMapContextTool() {
        if (hasGroupContext()) {
            long[] ctx = GROUP_CONTEXT.get();
            return groupMapContextService.groupMapsContext(ctx[0], ctx[1]);
        }
        return groupMapContextService.personalMapsContext(
                com.campus.security.user.SecurityUtils.getUserId());
    }

    private Map<String, Object> searchGroupMapPlacesTool(Map<String, Object> args) {
        String keyword = requiredText(args, "keyword");
        Integer limit = optionalInt(args, "limit");
        Long categoryId = args.get("categoryId") == null ? null : requiredLong(args, "categoryId");
        String source = scopeSource();
        List<GroupPlaceContext> results =
                groupMapContextService.searchPlaces(scopeMapIds(), keyword, categoryId, limit, source);
        List<GeoEvent.GeoPlace> geoPlaces = new ArrayList<>();
        for (GroupPlaceContext context : results) {
            geoPlaces.add(toGeoPlace(context, source));
        }
        collect(placesEvent(TOOL_SEARCH_GROUP_MAP_PLACES, source, geoPlaces,
                "关键词「" + keyword + "」，共 " + results.size() + " 个命中"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("keyword", keyword);
        result.put("total", results.size());
        result.put("results", results);
        result.put("note", "results 内 name/description/notes 均为真实数据；notes 为各授权地图下的成员备注原文。"
                + "空列表表示共建地图中没有匹配地点，可改关键词或经用户同意后用 search_places/amap_poi_search 扩大范围");
        return result;
    }

    private Map<String, Object> placeContextTool(Map<String, Object> args) {
        long placeId = requiredLong(args, "placeId");
        List<Long> scope = scopeMapIds();
        if (args.get("mapId") != null) {
            long mapId = requiredLong(args, "mapId");
            if (!scope.contains(mapId)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "该地图不在当前语境的授权范围内");
            }
            scope = List.of(mapId);
        }
        if (scope.isEmpty()) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "当前语境没有可访问的共建地图");
        }
        String source = scopeSource();
        GroupPlaceContext context = groupMapContextService.placeContext(scope, placeId, source);
        collect(placesEvent(TOOL_GET_PLACE_CONTEXT, source, List.of(toGeoPlace(context, source)), null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("place", context);
        return result;
    }

    // ---------- P1.5 确定性时空联合求解 ----------

    /**
     * plan_itinerary：忙闲（群体/个人）+ 候选地点 → SpatiotemporalPlanner 确定性求解。
     * 返回的快照即证据：方案全部带精确起止时间与交通段，禁止模型改写后提交。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> planItineraryTool(Map<String, Object> args) {
        LocalDateTime from = windowArg(args, "dateFrom");
        LocalDateTime to = windowArg(args, "dateTo");
        if (!to.isAfter(from)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "dateTo 必须晚于 dateFrom");
        }
        String mode = args.get("transportMode") == null ? "WALK" : requiredText(args, "transportMode");
        long buffer = args.get("bufferMinutes") == null
                ? SpatiotemporalPlanner.DEFAULT_BUFFER_MINUTES
                : Math.min(Math.max(requiredLong(args, "bufferMinutes"), 0), 60);

        // 往返行程：起点（通常来自 get_my_location）+ 可选返程段，由求解器统一排时间，模型不必手拼
        Double originLng = optionalNumber(args, "originLng");
        Double originLat = optionalNumber(args, "originLat");
        if ((originLng == null) != (originLat == null)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "originLng 与 originLat 必须同时提供");
        }
        PlannerModels.OriginPoint origin = null;
        if (originLng != null) {
            String originName = args.get("originName") == null ? "出发点" : requiredText(args, "originName").trim();
            origin = new PlannerModels.OriginPoint(
                    originName.length() > 32 ? originName.substring(0, 32) : originName, originLng, originLat);
        }
        boolean returnToStart = Boolean.TRUE.equals(args.get("returnToStart")) && origin != null;

        boolean group = hasGroupContext();
        long[] ctx = GROUP_CONTEXT.get();
        List<BusyEntryDto> busy;
        List<Map<String, Object>> coverage = new ArrayList<>();
        int unknownCount = 0;
        if (group) {
            GroupBusyDto groupBusy = scheduleService.groupBusy(ctx[0], ctx[1], from, to);
            busy = groupBusy.getKnown();
            unknownCount = groupBusy.getUnknownUserIds().size();
            for (Long userId : groupBusy.getKnown().stream()
                    .map(BusyEntryDto::getUserId).distinct().toList()) {
                coverage.add(coverageEntry(userId, "KNOWN"));
            }
            for (Long userId : groupBusy.getUnknownUserIds()) {
                if (coverage.stream().noneMatch(c -> c.get("userId").equals(userId))) {
                    coverage.add(coverageEntry(userId, "UNKNOWN"));
                }
            }
        } else {
            busy = scheduleService.myBusy(viewerId(), from, to);
            coverage.add(coverageEntry(viewerId(), "KNOWN"));
        }

        List<PlannerModels.PlannerPlace> candidates = itineraryCandidates(args);
        List<Long> stays = stayOverrides(args, candidates.size());

        List<PlannerModels.PlannerPlace> bounded = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            PlannerModels.PlannerPlace place = candidates.get(i);
            long stay = i < stays.size() ? stays.get(i) : SpatiotemporalPlanner.DEFAULT_STAY_MINUTES;
            bounded.add(new PlannerModels.PlannerPlace(place.placeId(), place.name(),
                    place.longitude(), place.latitude(), Math.min(Math.max(stay, 5), 600),
                    place.source(), place.sourceMapId(), place.rank()));
        }

        PlannerModels.PlannerOutcome outcome = SpatiotemporalPlanner.plan(
                busy, from, to, bounded, mode, buffer, buffer, travelInfoProvider, origin, returnToStart);

        // 首选方案同步进地理事件：前端预览站点与真实路线（模型最终提交哪个方案由它自己选）
        if (!outcome.solutions().isEmpty()) {
            PlannerModels.PlannerSolution best = outcome.solutions().get(0);
            GeoEvent itinerary = new GeoEvent();
            itinerary.setType(GeoEvent.TYPE_ITINERARY);
            itinerary.setTool(TOOL_PLAN_ITINERARY);
            itinerary.setSource(GeoEvent.SOURCE_ROUTE);
            itinerary.setNote("首选方案（评分最高）预览：点与折线来自确定性求解与真实路线");
            List<GeoEvent.GeoPlace> previewStops = new ArrayList<>();
            for (PlannerModels.PlannerStop stop : best.stops()) {
                GeoEvent.GeoPlace place = new GeoEvent.GeoPlace();
                place.setPlaceId(stop.placeId());
                place.setName(stop.name());
                place.setLongitude(stop.longitude());
                place.setLatitude(stop.latitude());
                place.setSource(GeoEvent.SOURCE_USER_LOCATION.equals(stop.source())
                        ? GeoEvent.SOURCE_USER_LOCATION : stop.source());
                place.setMapIds(stop.sourceMapId() == null ? null : List.of(stop.sourceMapId()));
                previewStops.add(place);
            }
            itinerary.setPlaces(previewStops);
            List<GeoEvent.GeoRouteSegment> previewSegments = new ArrayList<>();
            for (PlannerModels.PlannerSegment segment : best.segments()) {
                GeoEvent.GeoRouteSegment row = new GeoEvent.GeoRouteSegment();
                row.setFromSeq(segment.fromIndex() + 1);
                row.setToSeq(segment.toIndex() + 1);
                row.setTransportMode(segment.transportMode());
                row.setDistanceMeters(segment.distanceMeters());
                row.setDurationMinutes((int) segment.durationMinutes());
                row.setEstimated(segment.estimated());
                row.setPolyline(thin(segment.polyline()));
                previewSegments.add(row);
            }
            itinerary.setSegments(previewSegments);
            collect(itinerary);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", Map.of("start", TS.format(from), "end", TS.format(to)));
        result.put("transportMode", mode);
        result.put("bufferMinutes", buffer);
        if (origin != null) {
            result.put("origin", Map.of("name", origin.name(), "longitude", origin.longitude(),
                    "latitude", origin.latitude(), "returnToStart", returnToStart));
        }
        result.put("coverage", coverage);
        result.put("unknownCount", unknownCount);
        if (unknownCount > 0) {
            result.put("note", "存在未授权忙闲的成员，未计入窗口计算；方案为暂定，须其确认后方可视为可行");
        }
        List<Map<String, Object>> solutions = new ArrayList<>();
        boolean anyEstimatedRoute = false;
        for (PlannerModels.PlannerSolution solution : outcome.solutions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stops", solutionRows(solution));
            row.put("routeSegments", segmentRows(solution));
            row.put("totalTravelMinutes", solution.totalTravelMinutes());
            row.put("bufferRemainingMinutes", solution.bufferRemainingMinutes());
            row.put("groupMapStops", solution.groupMapStops());
            row.put("routeConfidence", solution.routeConfidence());
            row.put("score", Math.round(solution.score()));
            if ("ESTIMATED".equals(solution.routeConfidence())) {
                anyEstimatedRoute = true;
            }
            solutions.add(row);
        }
        result.put("solutions", solutions);
        if (anyEstimatedRoute) {
            result.put("routeNote", "部分方案交通耗时为估算值（高德不可用或超时），不能宣称真实路线已验证，请提示用户谨慎确认");
        }
        if (solutions.isEmpty()) {
            result.put("suggestions", outcome.suggestions());
        }
        result.put("note2", "方案时间由 Java 确定性求解器计算：请从 solutions 中选择最贴合用户需求的方案，"
                + "原样引用 stops 时间与 routeSegments 数据调用 submit_plan，不得改动时间或漏掉交通路线；"
                + "带 origin 的方案已含起点与返程站及其交通段，原样引用即可，严禁自行补站；"
                + "routeConfidence=ESTIMATED 的方案须向用户明示“交通为估算”");
        return result;
    }

    private Map<String, Object> coverageEntry(Long userId, String status) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("userId", userId);
        entry.put("status", status);
        return entry;
    }

    private List<Map<String, Object>> solutionRows(PlannerModels.PlannerSolution solution) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<PlannerModels.PlannerStop> stops = solution.stops();
        for (int i = 0; i < stops.size(); i++) {
            PlannerModels.PlannerStop stop = stops.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", i + 1);
            row.put("placeId", stop.placeId());
            row.put("name", stop.name());
            row.put("source", stop.source());
            row.put("sourceMapId", stop.sourceMapId());
            row.put("longitude", stop.longitude());
            row.put("latitude", stop.latitude());
            row.put("startTime", TS.format(stop.startTime()));
            row.put("endTime", TS.format(stop.endTime()));
            row.put("stayMinutes", stop.stayMinutes());
            row.put("transportMode", stop.transportMode());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> segmentRows(PlannerModels.PlannerSolution solution) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PlannerModels.PlannerSegment segment : solution.segments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fromSeq", segment.fromIndex() + 1);
            row.put("toSeq", segment.toIndex() + 1);
            row.put("transportMode", segment.transportMode());
            row.put("distanceMeters", segment.distanceMeters());
            row.put("durationMinutes", segment.durationMinutes());
            row.put("estimated", segment.estimated());
            rows.add(row);
        }
        return rows;
    }

    /** 候选解析：placeIds 显式优先（必须都在授权地图内），其次 keyword 语义检索取最相关 ≤4 */
    private List<PlannerModels.PlannerPlace> itineraryCandidates(Map<String, Object> args) {
        List<Long> scope = scopeMapIds();
        if (scope.isEmpty()) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "当前语境没有可访问的共建地图，无法联合求解");
        }
        String source = scopeSource();
        Object placeIdsArg = args.get("placeIds");
        List<Long> requested = new ArrayList<>();
        if (placeIdsArg instanceof List<?> ids) {
            for (Object item : ids) {
                Map<String, Object> single = Map.of("v", item);
                requested.add(requiredLong(single, "v"));
            }
        } else if (placeIdsArg != null) {
            requested.add(requiredLong(Map.of("v", placeIdsArg), "v"));
        }
        List<MapPlaceSearchItem> rows;
        if (!requested.isEmpty()) {
            if (requested.size() > SpatiotemporalPlanner.MAX_CANDIDATES) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "placeIds 最多 " + SpatiotemporalPlanner.MAX_CANDIDATES + " 个");
            }
            rows = groupMapContextService.placesInMaps(scope, requested);
            Map<Long, List<MapPlaceSearchItem>> byPlace = new LinkedHashMap<>();
            for (MapPlaceSearchItem row : rows) {
                byPlace.computeIfAbsent(row.getId(), key -> new ArrayList<>()).add(row);
            }
            List<PlannerModels.PlannerPlace> result = new ArrayList<>();
            for (int i = 0; i < requested.size(); i++) {
                List<MapPlaceSearchItem> candidates = byPlace.get(requested.get(i));
                if (candidates == null || candidates.isEmpty()) {
                    throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                            "第 " + (i + 1) + " 个 placeId 不在授权地图范围内，请先 search_group_map_places 确认");
                }
                result.add(toPlannerPlace(candidates.get(0), source, i));
            }
            return result;
        }
        String keyword = requiredText(args, "keyword");
        List<GroupPlaceContext> matched =
                groupMapContextService.searchPlaces(scope, keyword, null,
                        SpatiotemporalPlanner.MAX_CANDIDATES, source);
        if (matched.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(),
                    "共建地图中没有匹配「" + keyword + "」的地点，请先与用户确认候选");
        }
        List<PlannerModels.PlannerPlace> result = new ArrayList<>();
        for (int i = 0; i < matched.size(); i++) {
            GroupPlaceContext context = matched.get(i);
            result.add(new PlannerModels.PlannerPlace(context.getPlaceId(), context.getName(),
                    context.getLongitude(), context.getLatitude(),
                    SpatiotemporalPlanner.DEFAULT_STAY_MINUTES, source,
                    context.getMapIds().isEmpty() ? null : context.getMapIds().get(0), i));
        }
        return result;
    }

    private PlannerModels.PlannerPlace toPlannerPlace(MapPlaceSearchItem row, String source, int rank) {
        return new PlannerModels.PlannerPlace(row.getId(), row.getName(),
                row.getLongitude() == null ? 0 : row.getLongitude().doubleValue(),
                row.getLatitude() == null ? 0 : row.getLatitude().doubleValue(),
                SpatiotemporalPlanner.DEFAULT_STAY_MINUTES, source, row.getMapId(), rank);
    }

    /** stayMinutes 覆盖：与最终候选一一对应；越界/缺失回落默认值 */
    @SuppressWarnings("unchecked")
    private List<Long> stayOverrides(Map<String, Object> args, int candidateSize) {
        Object stays = args.get("stayMinutes");
        List<Long> result = new ArrayList<>();
        if (stays instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> single = Map.of("v", item);
                result.add(Math.min(Math.max(requiredLong(single, "v"), 5), 600));
            }
        }
        return result.size() > candidateSize
                ? result.subList(0, candidateSize).stream().toList()
                : result;
    }

    /**
     * submit_plan 语境盖章（AgentService 提交时调用）：把后端已知的群体语境写入草稿——
     * groupId、sourceMapIds、selectedPlaceIds、planningSourceVersion、逐站 source/sourceMapId。
     * 覆盖只发生在群语境；个人会话不写。该记忆不是模型给出的，不能被模型伪造。
     */
    public void stampGroupPlanContext(com.campus.ai.dto.PlanDto plan) {
        if (!hasGroupContext()) {
            return;
        }
        long[] ctx = GROUP_CONTEXT.get();
        List<Long> maps = groupMapContextService.groupMapIds(ctx[0], ctx[1]);
        plan.setGroupId(ctx[1]);
        if (maps.isEmpty()) {
            return;
        }
        plan.setSourceMapIds(maps);
        List<Long> placeIds = plan.getStops().stream()
                .map(com.campus.ai.dto.PlanStop::getPlaceId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (!placeIds.isEmpty()) {
            plan.setSelectedPlaceIds(placeIds);
            Map<Long, MapPlaceSearchItem> byPlace = new LinkedHashMap<>();
            for (MapPlaceSearchItem row : groupMapContextService.placesInMaps(maps, placeIds)) {
                byPlace.putIfAbsent(row.getId(), row);
            }
            for (com.campus.ai.dto.PlanStop stop : plan.getStops()) {
                // 实时起点/返程点不能被群来源盖章覆盖，否则保存时会重新落成 Place
                if (PlanSource.USER_LOCATION.equals(stop.getSource())) {
                    continue;
                }
                if (stop.getPlaceId() != null && byPlace.containsKey(stop.getPlaceId())) {
                    stop.setSource(PlanSource.GROUP_MAP);
                    stop.setMapId(byPlace.get(stop.getPlaceId()).getMapId());
                } else if (stop.getPlaceId() != null) {
                    stop.setSource(PlanSource.PERSONAL_MAP);
                } else {
                    stop.setSource(PlanSource.AMAP_POI);
                }
            }
        }
        try {
            plan.setPlanningSourceVersion(groupMapContextService.fingerprint(maps));
        } catch (Exception ex) {
            log.warn("planningSourceVersion 计算失败（提交暂不阻断）：{}", ex.toString());
        }
        stampGroupPlanPeople(plan, ctx[0], ctx[1], maps);
    }

    /**
     * 成员与冲突盖章：以群地图成员为准，列出每人的忙闲可见状态，并计算与计划时间冲突的安排。
     * 未授权成员不参与冲突计算，只写入 warnings；他人日程标题绝不进入 plan JSON。
     */
    private void stampGroupPlanPeople(com.campus.ai.dto.PlanDto plan, long viewerId, long groupId,
                                      List<Long> maps) {
        if (plan.getStartTime() == null || plan.getEndTime() == null || maps.isEmpty()) {
            return;
        }
        try {
            MapDetailResponse detail = sharedMapService.getMapDetailAs(maps.get(0), viewerId);
            if (detail == null || detail.getMembers() == null || detail.getMembers().isEmpty()) {
                return;
            }
            LocalDateTime from = plan.getStartTime().minusMinutes(30);
            LocalDateTime to = plan.getEndTime().plusMinutes(30);
            GroupBusyDto groupBusy = scheduleService.groupBusy(viewerId, groupId, from, to);
            Map<Long, String> nicknames = new LinkedHashMap<>();
            List<com.campus.ai.dto.PlanDto.PlanMember> memberRows = new ArrayList<>();
            int unknown = 0;
            for (MapMemberResponse member : detail.getMembers()) {
                if (member.getUserId() == null) {
                    continue;
                }
                com.campus.ai.dto.PlanDto.PlanMember row = new com.campus.ai.dto.PlanDto.PlanMember();
                row.setUserId(member.getUserId());
                row.setNickname(member.getNickname() != null && !member.getNickname().isBlank()
                        ? member.getNickname() : member.getUsername());
                row.setRole(member.getRole());
                boolean known = member.getUserId() == viewerId
                        || scheduleService.canSeeFreeBusy(member.getUserId(),
                        ScheduleSharePermission.TARGET_GROUP, groupId);
                row.setStatus(known ? "KNOWN" : "UNKNOWN");
                if (!known) {
                    unknown++;
                }
                memberRows.add(row);
                nicknames.put(member.getUserId(), row.getNickname());
            }
            plan.setMembers(memberRows);
            List<com.campus.ai.dto.PlanDto.PlanConflict> conflicts =
                    PlanConflictAnalyzer.analyze(plan, groupBusy.getKnown(), nicknames);
            plan.setConflicts(conflicts.isEmpty() ? null : conflicts);
            // 保留校验阶段写入的 warnings（如交通 PARTIAL），不要整包覆盖
            List<String> warnings = plan.getWarnings() == null
                    ? new ArrayList<>() : new ArrayList<>(plan.getWarnings());
            if (unknown > 0) {
                warnings.add(unknown + " 位成员未授权忙闲，冲突判断不完整，方案暂定");
            }
            plan.setWarnings(warnings.isEmpty() ? null : warnings);
            if (!conflicts.isEmpty()) {
                plan.setConstraintStatus("CONFLICT");
            } else if (unknown > 0) {
                plan.setConstraintStatus("TENTATIVE");
            } else {
                plan.setConstraintStatus("FEASIBLE");
            }
        } catch (Exception ex) {
            log.warn("群体成员/冲突盖章失败（不阻断草稿）：{}", ex.toString());
        }
    }


    private String jsonify(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message == null ? "工具失败" : message));
        } catch (Exception ex) {
            return "{\"error\":\"工具失败\"}";
        }
    }

    private Map<String, Object> function(String name, String description, Map<String, Object> parameters, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>(parameters);
        schema.put("required", required);
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", schema);
        return fn;
    }

    private Map<String, Object> props(Map<String, ? extends Object> properties) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "object");
        map.put("properties", properties);
        return map;
    }

    private Map<String, Object> type(String type, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        map.put("description", description);
        return map;
    }

    private Map<String, Object> enumType(String description, String... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "string");
        map.put("description", description);
        map.put("enum", List.of(values));
        return map;
    }
}
