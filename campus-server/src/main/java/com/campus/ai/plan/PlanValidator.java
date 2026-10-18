package com.campus.ai.plan;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿独立校验层（评审 P0）：模型输出绝不直接入库。
 * Schema 字段 / 起止时间 / 站点顺序 / 坐标合法性 / placeId 存在性与可见性 / 业务约束全部在此把关。
 * P1.5 扩展：未知字段宽容解析（向后兼容）、来源/覆盖字段整形，站点间交通运输时间硬校验
 * （真实耗时 ≤ 站点间隔，两小时排不了三个地点），总交通耗时由重算覆写，不信任模型给值。
 */
@Slf4j
@Component
public class PlanValidator {

    private static final List<String> CATEGORIES = List.of("MEETING", "COMPETITION", "OTHER");
    private static final List<String> TRANSPORTS = List.of("WALK", "RIDE", "DRIVE", "TRANSIT");
    private static final List<String> SOURCES = List.of(PlanSource.GROUP_MAP, PlanSource.PERSONAL_MAP,
            PlanSource.AMAP_POI);
    private static final int MAX_STOPS = 8;
    private static final int MAX_SEGMENT_CHECKS = 8;
    private static final java.time.format.DateTimeFormatter HHMMSS =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss");

    private final ObjectMapper objectMapper;
    private final ObjectMapper lenientMapper;
    private final PlaceService placeService;
    private final SharedMapService sharedMapService;
    private final TravelInfoProvider travelInfoProvider;

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
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public PlanDto parseAndValidate(String planJson, Long userId) {
        PlanDto plan;
        try {
            plan = lenientMapper.readValue(planJson, PlanDto.class);
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "规划草稿 JSON 无法解析");
        }
        return validate(plan, userId);
    }

    public PlanDto validate(PlanDto plan, Long userId) {
        if (plan == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "规划草稿为空");
        }
        if (!StringUtils.hasText(plan.getTitle())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动标题缺失");
        }
        plan.setTitle(plan.getTitle().trim());
        if (plan.getTitle().length() > 128) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动标题过长");
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
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动类型无效");
        }
        if (plan.getStartTime() == null || plan.getEndTime() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动起止时间缺失");
        }
        if (!plan.getEndTime().isAfter(plan.getStartTime())) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动结束时间必须晚于开始时间");
        }
        if (plan.getStartTime().isBefore(LocalDateTime.now().minusMinutes(2))) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "活动开始时间已过去，请调整为未来时间");
        }
        if (plan.getMaxMembers() == null) {
            plan.setMaxMembers(20);
        }
        if (plan.getMaxMembers() < 1 || plan.getMaxMembers() > 500) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "人数上限须在 1-500");
        }
        if (plan.getStops() == null || plan.getStops().isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "行程站点至少 1 个");
        }
        if (plan.getStops().size() > MAX_STOPS) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "行程站点最多 " + MAX_STOPS + " 个");
        }
        LocalDateTime reached = null;
        for (int i = 0; i < plan.getStops().size(); i++) {
            PlanStop stop = plan.getStops().get(i);
            if (!StringUtils.hasText(stop.getName())) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "第 " + (i + 1) + " 个站点缺少名称");
            }
            stop.setName(stop.getName().trim());
            if (stop.getName().length() > 128) {
                stop.setName(stop.getName().substring(0, 128));
            }
            if (stop.getPlaceId() != null) {
                Place place = placeService.requireActivePlace(stop.getPlaceId());
                stop.setLongitude(place.getLongitude().doubleValue());
                stop.setLatitude(place.getLatitude().doubleValue());
            } else {
                if (stop.getLongitude() == null || stop.getLatitude() == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "站点「" + stop.getName() + "」缺少地点或坐标");
                }
                if (stop.getLongitude() < -180 || stop.getLongitude() > 180
                        || stop.getLatitude() < -90 || stop.getLatitude() > 90) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "站点「" + stop.getName() + "」坐标无效");
                }
            }
            if (stop.getTransportMode() != null && !TRANSPORTS.contains(stop.getTransportMode())) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                        "站点「" + stop.getName() + "」交通方式无效");
            }
            if (stop.getSource() != null && !SOURCES.contains(stop.getSource())) {
                // 模型给的来源标签只保留可识别值；权威覆写在提交盖章阶段
                stop.setSource(null);
            }
            if (stop.getStayMinutes() != null) {
                long stay = stop.getStayMinutes().longValue();
                stop.setStayMinutes((int) Math.min(Math.max(stay, 5), 600));
            }
            if (stop.getStartTime() != null) {
                if (stop.getStartTime().isBefore(plan.getStartTime())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "站点「" + stop.getName() + "」早于活动开始时间");
                }
                if (reached != null && stop.getStartTime().isBefore(reached)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "站点时间顺序不正确");
                }
                reached = stop.getStartTime();
            }
            if (stop.getEndTime() != null) {
                if (stop.getStartTime() != null && stop.getEndTime().isBefore(stop.getStartTime())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "站点「" + stop.getName() + "」结束时间早于开始时间");
                }
                if (stop.getEndTime().isAfter(plan.getEndTime())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST.getCode(),
                            "站点「" + stop.getName() + "」晚于活动结束时间");
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
        if (plan.getReferenceMapId() != null) {
            // 复用共享地图的可见性校验：非成员且非公开即拒绝（防止引用他人地图）。
            // 校验可能发生在群聊 agent 线程（无 SecurityContext），必须显式传 userId
            sharedMapService.getMapDetailAs(plan.getReferenceMapId(), userId);
        }
        return plan;
    }

    /** P1.5 扩展字段形状校验；随后做站点间交通运输可行性硬校验 */
    private void validateSpatialExtension(PlanDto plan) {
        if (plan.getSourceMapIds() != null) {
            List<Long> distinct = plan.getSourceMapIds().stream().distinct().toList();
            if (distinct.size() > 8) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "方案关联共享地图最多 8 张");
            }
            plan.setSourceMapIds(distinct);
        }
        if (plan.getSelectedPlaceIds() != null) {
            List<Long> distinct = plan.getSelectedPlaceIds().stream().distinct().toList();
            if (distinct.size() > 16) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "方案选用地点过多");
            }
            plan.setSelectedPlaceIds(distinct);
        }
        if (plan.getAvailabilityCoverage() != null) {
            if (plan.getAvailabilityCoverage().size() > 100) {
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "忙闲覆盖数据异常");
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
                && !"TENTATIVE".equals(plan.getConstraintStatus())) {
            plan.setConstraintStatus(null);
        }
        // routeConfidence 不信任模型自述，清空后由交通段重算覆写
        plan.setRouteConfidence(null);
        validateTravelFeasibility(plan);
    }

    /**
     * 站点间交通运输硬校验：相邻两站都含坐标且起止时间齐备时，实际交通耗时必须 ≤ 两站间隔，否则拒绝提交。
     * 高德不可用退化为直线估算（provider 标记 estimated），仍然按耗时校验——这正是“两个小时排三个地点”的兜底线。
     * 依据本次重算结果覆写 routeSegments 与 totalTravelMinutes，模型给值不作数。
     */
    private void validateTravelFeasibility(PlanDto plan) {
        List<PlanStop> stops = plan.getStops();
        List<PlanDto.PlanRouteSegment> segments = new ArrayList<>();
        long total = 0;
        boolean anyEstimated = false;
        for (int i = 0; i < stops.size() - 1; i++) {
            PlanStop current = stops.get(i);
            PlanStop next = stops.get(i + 1);
            if (current.getEndTime() == null || next.getStartTime() == null
                    || current.getLongitude() == null || next.getLongitude() == null) {
                continue;
            }
            String mode = "RIDE".equals(next.getTransportMode()) ? "RIDE"
                    : "DRIVE".equals(next.getTransportMode()) ? "DRIVE"
                    : "TRANSIT".equals(next.getTransportMode()) ? "TRANSIT" : "WALK";
            if ("TRANSIT".equals(mode)) {
                continue; // 公交暂无可靠路线接口，不作为硬校验依据
            }
            RouteInfo route = travelInfoProvider.searchRoute(mode,
                    current.getLongitude(), current.getLatitude(),
                    next.getLongitude(), next.getLatitude());
            if (route.getDurationSeconds() == null) {
                continue;
            }
            long travelSeconds = Math.max(0, route.getDurationSeconds());
            long gapSeconds = Duration.between(current.getEndTime(), next.getStartTime()).getSeconds();
            long travelMinutes = (travelSeconds + 59) / 60;
            anyEstimated |= route.isEstimated() || "estimate".equals(route.getProvider());
            PlanDto.PlanRouteSegment segment = new PlanDto.PlanRouteSegment();
            segment.setFromSeq(i + 1);
            segment.setToSeq(i + 2);
            segment.setTransportMode(mode);
            segment.setDistanceMeters(route.getDistanceMeters());
            segment.setDurationMinutes((int) travelMinutes);
            segments.add(segment);
            total += travelMinutes;
            if (segments.size() > MAX_SEGMENT_CHECKS) {
                break;
            }
            // 按秒比较：此前 gap 向下取整、耗时向上取整，按真实秒数留间隔也可能差 1 分钟被拒；
            // 文案给出可执行的"最早开始时间"与交通方式口径（未填 transportMode 按步行估算）
            if (gapSeconds < travelSeconds) {
                LocalDateTime earliest = current.getEndTime().plusSeconds(travelSeconds);
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), String.format(
                        "「%s」→「%s」%s约 %d 分钟，但时间表只留了 %d 分钟；请把「%s」的开始时间设为 %s 或更晚"
                                + "（该段按目标站交通方式计算，未填写时按步行估算）",
                        current.getName(), next.getName(), transportLabel(mode), travelMinutes,
                        Math.max(0, gapSeconds) / 60, next.getName(), earliest.format(HHMMSS)));
            }
        }
        if (!segments.isEmpty()) {
            plan.setRouteSegments(segments);
            plan.setTotalTravelMinutes((int) total);
            plan.setRouteConfidence(anyEstimated ? "ESTIMATED" : "AMAP");
        }
    }

    private String transportLabel(String mode) {
        return switch (mode) {
            case "RIDE" -> "骑行";
            case "DRIVE" -> "驾车";
            case "TRANSIT" -> "公交";
            default -> "步行";
        };
    }
}
