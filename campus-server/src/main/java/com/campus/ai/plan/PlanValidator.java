package com.campus.ai.plan;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.travel.ItineraryTravelChecker;
import com.campus.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.map.service.SharedMapService;
import com.campus.place.entity.Place;
import com.campus.place.service.PlaceService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿独立校验层（评审 P0）：模型输出绝不直接入库。
 * Schema 字段 / 起止时间 / 站点顺序 / 坐标合法性 / placeId 存在性与可见性 / 业务约束全部在此把关。
 * P1.5 扩展：未知字段宽容解析（向后兼容）、来源/覆盖字段整形，站点间交通运输时间硬校验
 * （真实耗时 ≤ 站点间隔，两小时排不了三个地点），总交通耗时由重算覆写，不信任模型给值。
 *
 * <p>P0 Trace：所有失败改为 {@link PlanValidationException}，携带稳定 ruleId 与结构化 details；
 * 用户可见文案与 HTTP code 保持不变。</p>
 */
@Slf4j
@Component
public class PlanValidator {

    /** 校验规则集合版本；plan/validate 事件记录它，便于后续规则演进对比 */
    public static final String RULE_SET = "plan-v1";

    private static final List<String> CATEGORIES = List.of("MEETING", "COMPETITION", "OTHER");
    private static final List<String> TRANSPORTS = List.of("WALK", "RIDE", "DRIVE", "TAXI", "TRANSIT");
    private static final List<String> SOURCES = List.of(PlanSource.GROUP_MAP, PlanSource.PERSONAL_MAP,
            PlanSource.AMAP_POI, PlanSource.USER_LOCATION);
    private static final int MAX_STOPS = 8;
    private static final java.time.format.DateTimeFormatter HHMMSS =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final ObjectMapper lenientMapper;
    private final PlaceService placeService;
    private final SharedMapService sharedMapService;
    private final TravelInfoProvider travelInfoProvider;
    private final ItineraryTravelChecker travelChecker;

    public PlanValidator(@Qualifier("redisObjectMapper") ObjectMapper objectMapper,
                         PlaceService placeService,
                         SharedMapService sharedMapService,
                         TravelInfoProvider travelInfoProvider) {
        this.objectMapper = objectMapper;
        this.lenientMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.placeService = placeService;
        this.sharedMapService = sharedMapService;
        this.travelInfoProvider = travelInfoProvider;
        this.travelChecker = new ItineraryTravelChecker(travelInfoProvider);
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public PlanDto parseAndValidate(String planJson, Long userId) {
        return parseAndValidate(planJson, userId, false);
    }

    public PlanDto parseAndValidate(String planJson, Long userId, boolean groupContext) {
        PlanDto plan;
        try {
            plan = lenientMapper.readValue(planJson, PlanDto.class);
        } catch (Exception ex) {
            throw new PlanValidationException("PLAN_JSON_INVALID", "规划草稿 JSON 无法解析");
        }
        return validate(plan, userId, groupContext);
    }

    public PlanDto validate(PlanDto plan, Long userId) {
        return validate(plan, userId, false);
    }

    public PlanDto validate(PlanDto plan, Long userId, boolean groupContext) {
        if (plan == null) {
            throw new PlanValidationException("PLAN_EMPTY", "规划草稿为空");
        }
        if (!StringUtils.hasText(plan.getTitle())) {
            throw new PlanValidationException("TITLE_MISSING", "活动标题缺失");
        }
        plan.setTitle(plan.getTitle().trim());
        if (plan.getTitle().length() > 128) {
            throw new PlanValidationException("TITLE_TOO_LONG", "活动标题过长");
        }
        if (plan.getSummary() != null) {
            plan.setSummary(plan.getSummary().trim());
            if (plan.getSummary().isEmpty()) {
                plan.setSummary(null);
            } else if (plan.getSummary().length() > 1000) {
                plan.setSummary(plan.getSummary().substring(0, 1000));
            }
        }
        if (plan.getCategory() == null || plan.getCategory().isBlank()) {
            plan.setCategory("OTHER");
        } else if (!CATEGORIES.contains(plan.getCategory())) {
            throw new PlanValidationException("CATEGORY_INVALID", "活动类型无效");
        }
        if (plan.getStartTime() == null || plan.getEndTime() == null) {
            throw new PlanValidationException("TIME_MISSING", "活动起止时间缺失");
        }
        if (!plan.getEndTime().isAfter(plan.getStartTime())) {
            throw new PlanValidationException("TIME_ORDER_INVALID", "活动结束时间必须晚于开始时间");
        }
        if (plan.getStartTime().isBefore(LocalDateTime.now().minusMinutes(2))) {
            throw new PlanValidationException("TIME_IN_PAST", "活动开始时间已过去，请调整为未来时间");
        }
        if (plan.getMaxMembers() == null) {
            plan.setMaxMembers(20);
        }
        if (plan.getMaxMembers() < 1 || plan.getMaxMembers() > 500) {
            throw new PlanValidationException("MAX_MEMBERS_INVALID", "人数上限须在 1-500");
        }
        if (plan.getStops() == null || plan.getStops().isEmpty()) {
            throw new PlanValidationException("STOPS_EMPTY", "行程站点至少 1 个");
        }
        if (plan.getStops().size() > MAX_STOPS) {
            throw new PlanValidationException("STOPS_TOO_MANY", "行程站点最多 " + MAX_STOPS + " 个");
        }
        LocalDateTime reached = null;
        for (int i = 0; i < plan.getStops().size(); i++) {
            PlanStop stop = plan.getStops().get(i);
            if (!StringUtils.hasText(stop.getName())) {
                throw fail("STOP_NAME_MISSING", "第 " + (i + 1) + " 个站点缺少名称",
                        Map.of("stopSeq", i + 1));
            }
            stop.setName(stop.getName().trim());
            if (stop.getName().length() > 128) {
                stop.setName(stop.getName().substring(0, 128));
            }
            if (stop.getPlaceId() != null) {
                Place place;
                try {
                    place = placeService.requireActivePlace(stop.getPlaceId());
                } catch (BusinessException ex) {
                    throw new PlanValidationException(ex.getCode(), "PLACE_NOT_FOUND", ex.getMessage(),
                            Map.of("stopSeq", i + 1, "placeId", stop.getPlaceId()));
                }
                stop.setLongitude(place.getLongitude().doubleValue());
                stop.setLatitude(place.getLatitude().doubleValue());
            } else {
                if (stop.getLongitude() == null || stop.getLatitude() == null) {
                    throw fail("STOP_LOCATION_MISSING",
                            "站点「" + stop.getName() + "」缺少地点或坐标",
                            Map.of("stopSeq", i + 1));
                }
                if (stop.getLongitude() < -180 || stop.getLongitude() > 180
                        || stop.getLatitude() < -90 || stop.getLatitude() > 90) {
                    throw fail("STOP_COORD_INVALID",
                            "站点「" + stop.getName() + "」坐标无效",
                            Map.of("stopSeq", i + 1));
                }
            }
            if (stop.getTransportMode() != null && !TRANSPORTS.contains(stop.getTransportMode())) {
                throw fail("STOP_TRANSPORT_INVALID",
                        "站点「" + stop.getName() + "」交通方式无效",
                        Map.of("stopSeq", i + 1));
            }
            if (stop.getSource() != null && !SOURCES.contains(stop.getSource())) {
                // 模型给的来源标签只保留可识别值；权威覆写在提交盖章阶段
                stop.setSource(null);
            }
            if (PlanSource.USER_LOCATION.equals(stop.getSource()) && stop.getPlaceId() != null) {
                throw fail("USER_LOCATION_WITH_PLACE", "实时起点站点不能关联地点",
                        Map.of("stopSeq", i + 1));
            }
            if (stop.getStayMinutes() != null) {
                long stay = stop.getStayMinutes().longValue();
                stop.setStayMinutes((int) Math.min(Math.max(stay, 5), 600));
            }
            if (stop.getStartTime() != null) {
                if (stop.getStartTime().isBefore(plan.getStartTime())) {
                    throw fail("STOP_TIME_BEFORE_ACTIVITY",
                            "站点「" + stop.getName() + "」早于活动开始时间",
                            Map.of("stopSeq", i + 1));
                }
                if (reached != null && stop.getStartTime().isBefore(reached)) {
                    throw fail("STOP_TIME_ORDER_INVALID", "站点时间顺序不正确",
                            Map.of("stopSeq", i + 1));
                }
                reached = stop.getStartTime();
            }
            if (stop.getEndTime() != null) {
                if (stop.getStartTime() != null && stop.getEndTime().isBefore(stop.getStartTime())) {
                    throw fail("STOP_END_BEFORE_START",
                            "站点「" + stop.getName() + "」结束时间早于开始时间",
                            Map.of("stopSeq", i + 1));
                }
                if (stop.getEndTime().isAfter(plan.getEndTime())) {
                    throw fail("STOP_END_AFTER_ACTIVITY",
                            "站点「" + stop.getName() + "」晚于活动结束时间",
                            Map.of("stopSeq", i + 1));
                }
                if (reached == null || stop.getEndTime().isAfter(reached)) {
                    reached = stop.getEndTime();
                }
            }
            if (stop.getNote() != null && stop.getNote().length() > 256) {
                stop.setNote(stop.getNote().substring(0, 256));
            }
            stop.setSeq(i + 1);
        }
        validateSpatialExtension(plan);
        if (groupContext) {
            // 群语境下参考地图由服务端 sourceMapId 盖章；模型给的 referenceMapId 不可信，直接丢弃
            plan.setReferenceMapId(null);
        } else if (plan.getReferenceMapId() != null) {
            // 复用共享地图的可见性校验：非成员且非公开即拒绝（防止引用他人地图）。
            // 校验可能发生在群聊 agent 线程（无 SecurityContext），必须显式传 userId
            try {
                sharedMapService.getMapDetailAs(plan.getReferenceMapId(), userId);
            } catch (BusinessException ex) {
                throw new PlanValidationException(ex.getCode(), "REFERENCE_MAP_FORBIDDEN",
                        ex.getMessage(), Map.of("mapId", plan.getReferenceMapId()));
            }
        }
        return plan;
    }

    /** P1.5 扩展字段形状校验；随后做站点间交通运输可行性硬校验 */
    private void validateSpatialExtension(PlanDto plan) {
        if (plan.getSourceMapIds() != null) {
            List<Long> distinct = plan.getSourceMapIds().stream().distinct().toList();
            if (distinct.size() > 8) {
                throw fail("SOURCE_MAP_TOO_MANY", "方案关联共享地图最多 8 张",
                        Map.of("limit", 8, "actual", distinct.size()));
            }
            plan.setSourceMapIds(distinct);
        }
        if (plan.getSelectedPlaceIds() != null) {
            List<Long> distinct = plan.getSelectedPlaceIds().stream().distinct().toList();
            if (distinct.size() > 16) {
                throw fail("PLACE_TOO_MANY", "方案选用地点过多",
                        Map.of("limit", 16, "actual", distinct.size()));
            }
            plan.setSelectedPlaceIds(distinct);
        }
        if (plan.getAvailabilityCoverage() != null) {
            if (plan.getAvailabilityCoverage().size() > 100) {
                throw fail("COVERAGE_TOO_LARGE", "忙闲覆盖数据异常",
                        Map.of("limit", 100, "actual", plan.getAvailabilityCoverage().size()));
            }
            List<PlanDto.PlanCoverage> coverage = new ArrayList<>();
            for (PlanDto.PlanCoverage item : plan.getAvailabilityCoverage()) {
                if (item == null || item.getUserId() == null || item.getStatus() == null) {
                    continue;
                }
                if ("KNOWN".equals(item.getStatus()) || "UNKNOWN".equals(item.getStatus())) {
                    coverage.add(item);
                }
            }
            plan.setAvailabilityCoverage(coverage.isEmpty() ? null : coverage);
        }
        if (plan.getReason() != null) {
            plan.setReason(plan.getReason().trim());
            if (plan.getReason().isEmpty()) {
                plan.setReason(null);
            } else if (plan.getReason().length() > 500) {
                plan.setReason(plan.getReason().substring(0, 500));
            }
        }
        if (plan.getConstraintStatus() != null
                && !"FEASIBLE".equals(plan.getConstraintStatus())
                && !"TENTATIVE".equals(plan.getConstraintStatus())
                && !"CONFLICT".equals(plan.getConstraintStatus())) {
            plan.setConstraintStatus(null);
        }
        // routeConfidence 不信任模型自述，清空后由交通段重算覆写
        plan.setRouteConfidence(null);
        validateTravelFeasibility(plan);
    }

    /**
     * 站点间交通运输三态校验：不可行优先于未核验。
     * 高德不可用退化为直线估算（provider 标记 estimated），仍然按耗时校验；
     * 缺字段/超段只标 PARTIAL，不能宣称“已全部核验”。依据本次重算结果覆写
     * routeSegments 与 totalTravelMinutes，模型给值不作数。
     */
    private void validateTravelFeasibility(PlanDto plan) {
        List<ItineraryTravelChecker.Leg> legs = new ArrayList<>();
        for (int i = 0; i < plan.getStops().size(); i++) {
            PlanStop stop = plan.getStops().get(i);
            ItineraryTravelChecker.Leg leg = new ItineraryTravelChecker.Leg();
            leg.setSeq(i + 1);
            leg.setName(stop.getName());
            leg.setLongitude(stop.getLongitude());
            leg.setLatitude(stop.getLatitude());
            leg.setStartTime(stop.getStartTime());
            leg.setEndTime(stop.getEndTime());
            leg.setTransportMode(stop.getTransportMode());
            legs.add(leg);
        }
        ItineraryTravelChecker.Result result = travelChecker.check(legs);
        // 1) 已确认不可行：即使其他段 provider 异常，也必须先报这一条可执行的冲突
        if (!result.getViolations().isEmpty()) {
            ItineraryTravelChecker.Violation violation = result.getViolations().get(0);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("fromSeq", violation.getFromSeq());
            details.put("toSeq", violation.getToSeq());
            details.put("transportMode", violation.getTransportMode());
            details.put("availableMinutes", violation.getAvailableMinutes());
            details.put("requiredMinutes", (long) violation.getRequiredMinutes());
            details.put("earliestStart", violation.getEarliestStart().format(HHMMSS));
            throw new PlanValidationException(ResultCode.BAD_REQUEST.getCode(),
                    "TRAVEL_TIME_CONFLICT", ItineraryTravelChecker.message(violation), details);
        }
        // 2) AI 路径保持严格：provider 异常直接拒绝，避免把未知当可行
        if (result.hasProviderError()) {
            ItineraryTravelChecker.Unverified error = result.getUnverified().stream()
                    .filter(u -> u.getReason() == ItineraryTravelChecker.UnverifiedReason.PROVIDER_ERROR)
                    .findFirst().orElseThrow();
            throw failWithCode(error.getCode() == null ? ResultCode.BAD_REQUEST.getCode() : error.getCode(),
                    "ROUTE_ESTIMATE_FAILED", error.getDetail(),
                    Map.of("fromSeq", error.getFromSeq(), "toSeq", error.getToSeq()));
        }
        if (!result.getSegments().isEmpty()) {
            List<PlanDto.PlanRouteSegment> segments = new ArrayList<>();
            for (ItineraryTravelChecker.Segment source : result.getSegments()) {
                PlanDto.PlanRouteSegment segment = new PlanDto.PlanRouteSegment();
                segment.setFromSeq(source.getFromSeq());
                segment.setToSeq(source.getToSeq());
                segment.setTransportMode(source.getTransportMode());
                segment.setDistanceMeters(source.getDistanceMeters());
                segment.setDurationMinutes(source.getDurationMinutes());
                segments.add(segment);
            }
            plan.setRouteSegments(segments);
            plan.setTotalTravelMinutes(result.getTotalMinutes());
        }
        if (result.fullyVerified()) {
            if (!result.getSegments().isEmpty()) {
                plan.setRouteConfidence(result.isAnyEstimated() ? "ESTIMATED" : "AMAP");
            }
        } else {
            // 3) 缺字段/超段：明确标部分未核验，不冒充全部通过
            plan.setRouteConfidence("PARTIAL");
            List<String> warnings = plan.getWarnings() == null ? new ArrayList<>() : plan.getWarnings();
            warnings.add("部分站点缺少时间/坐标或超出校验段数，未做交通校验");
            plan.setWarnings(warnings);
        }
    }

    private PlanValidationException fail(String ruleId, String message, Map<String, Object> details) {
        return new PlanValidationException(ResultCode.BAD_REQUEST.getCode(), ruleId, message, details);
    }

    private PlanValidationException failWithCode(int code, String ruleId, String message,
                                                 Map<String, Object> details) {
        return new PlanValidationException(code, ruleId, message, details);
    }
}
