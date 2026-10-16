package com.campus.ai.tool;

import com.campus.ai.dto.GroupPlaceContext;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.planner.FreeWindow;
import com.campus.ai.planner.FreeWindowSolver;
import com.campus.ai.planner.PlannerModels;
import com.campus.ai.planner.SpatiotemporalPlanner;
import com.campus.ai.plan.PlanSource;
import com.campus.ai.service.GroupMapContextService;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.util.SqlLikeUtils;
import com.campus.map.dto.MapPlaceSearchItem;
import com.campus.map.service.SharedMapService;
import com.campus.place.mapper.PlaceMapper;
import com.campus.schedule.dto.BusyEntryDto;
import com.campus.schedule.dto.GroupBusyDto;
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

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final SharedMapService sharedMapService;
    private final PlaceMapper placeMapper;
    private final TravelInfoProvider travelInfoProvider;
    private final ObjectMapper objectMapper;
    private final ScheduleService scheduleService;
    private final GroupMapContextService groupMapContextService;

    /** 群语境（由后端从 ws 会话注入，模型不可指定）：key1=viewerId key2=campus_group.id */
    private static final ThreadLocal<long[]> GROUP_CONTEXT = new ThreadLocal<>();

    public static void setGroupContext(long viewerId, long groupId) {
        GROUP_CONTEXT.set(new long[]{viewerId, groupId});
    }

    public static void clearGroupContext() {
        GROUP_CONTEXT.remove();
    }

    private boolean hasGroupContext() {
        long[] ctx = GROUP_CONTEXT.get();
        return ctx != null && ctx[0] > 0 && ctx[1] > 0;
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
        functions.add(function(TOOL_PLAN_ITINERARY,
                "确定性时空联合求解：给日期窗口与候选地点（placeIds 或 keyword），由系统计算共同空闲、真实交通耗时与站点顺序，"
                        + "返回最多 3 个带回精确起止时间的可行方案；无解时返回原因与建议。方案时间必须原样引用，不得自行编造或调整",
                props(Map.of(
                        "dateFrom", type("string", "窗口开始 yyyy-MM-ddTHH:mm"),
                        "dateTo", type("string", "窗口结束 yyyy-MM-ddTHH:mm"),
                        "placeIds", Map.of("type", "array", "items", Map.of("type", "integer"),
                                "description", "候选地点（≤4 个，来自 search_group_map_places / list_map_places）"),
                        "keyword", type("string", "可选：共建地图语义检索，自动取最相关 ≤4 个地点"),
                        "stayMinutes", Map.of("type", "array", "items", Map.of("type", "integer"),
                                "description", "可选：与最终候选一一对应的停留分钟数（缺省 60）"),
                        "transportMode", enumType("出行方式（公交不支持联合求解）", "WALK", "RIDE", "DRIVE"),
                        "bufferMinutes", type("integer", "可选：最后一站后保留缓冲分钟数（缺省 10）"))),
                List.of("dateFrom", "dateTo")));
        Map<String, Object> stopItemProps = new LinkedHashMap<>();
        stopItemProps.put("name", type("string", "站点名称"));
        stopItemProps.put("placeId", type("integer", "系统地点ID（有则优先）"));
        stopItemProps.put("longitude", type("number", "GCJ-02 经度"));
        stopItemProps.put("latitude", type("number", "GCJ-02 纬度"));
        stopItemProps.put("startTime", type("string", "到达/开始时间"));
        stopItemProps.put("endTime", type("string", "离开/结束时间"));
        stopItemProps.put("transportMode", enumType("前往本站的交通方式", "WALK", "RIDE", "DRIVE", "TRANSIT"));
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
        submitPlanProps.put("totalTravelMinutes", type("integer", "可选：总交通耗时分钟数（来自 plan_itinerary）"));
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
                .map(fn -> Map.<String, Object>of("type", "function", "function", fn))
                .toList();
    }

    /** 执行工具并回填 JSON；权限一律走业务层校验，异常转为 error JSON 让模型自行纠正 */
    public String execute(String name, String argsJson) {
        try {
            Map<String, Object> args = objectMapper.readValue(argsJson, new TypeReference<>() {
            });
            return switch (name) {
                case TOOL_LIST_MY_MAPS -> jsonify(sharedMapService.listMyMaps());
                case TOOL_LIST_MAP_PLACES -> jsonify(sharedMapService.listMapPlaces(
                        requiredLong(args, "mapId")));
                case TOOL_SEARCH_PLACES -> jsonify(placeMapper.searchPlaces(
                        SqlLikeUtils.escapeLike(requiredText(args, "keyword")), 10));
                case TOOL_GET_GROUP_MAP_CONTEXT -> jsonify(groupMapContextTool());
                case TOOL_SEARCH_GROUP_MAP_PLACES -> jsonify(searchGroupMapPlacesTool(args));
                case TOOL_GET_PLACE_CONTEXT -> jsonify(placeContextTool(args));
                case TOOL_PLAN_ITINERARY -> jsonify(planItineraryTool(args));
                case TOOL_GET_MY_SCHEDULE -> jsonify(myScheduleTool(args));
                case TOOL_FIND_FREE_WINDOWS -> jsonify(findFreeWindowsTool(args, hasGroupContext()));
                case TOOL_ROUTE_INFO -> {
                    RouteInfo route = travelInfoProvider.searchRoute(
                            requiredText(args, "mode"),
                            requiredNumber(args, "fromLng"),
                            requiredNumber(args, "fromLat"),
                            requiredNumber(args, "toLng"),
                            requiredNumber(args, "toLat"));
                    Map<String, Object> compact = new LinkedHashMap<>();
                    compact.put("mode", route.getMode());
                    compact.put("distanceMeters", route.getDistanceMeters());
                    compact.put("durationSeconds", route.getDurationSeconds());
                    compact.put("durationMinutes", route.getDurationSeconds() == null
                            ? null
                            : Math.round(route.getDurationSeconds() / 6.0) / 10.0);
                    compact.put("estimated", route.isEstimated());
                    compact.put("provider", route.getProvider());
                    yield jsonify(compact);
                }
                case TOOL_AMAP_POI_SEARCH -> jsonify(travelInfoProvider.searchPoi(
                        requiredText(args, "keyword"),
                        optionalNumber(args, "nearLng"),
                        optionalNumber(args, "nearLat"),
                        optionalInt(args, "radiusMeters")));
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
                busy, from, to, bounded, mode, buffer, buffer, travelInfoProvider);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", Map.of("start", TS.format(from), "end", TS.format(to)));
        result.put("transportMode", mode);
        result.put("bufferMinutes", buffer);
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
