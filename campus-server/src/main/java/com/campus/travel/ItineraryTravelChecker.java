package com.campus.travel;

import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 站点间交通可行性校验（三态）：可行 / 不可行 / 未核验。
 *
 * <p>不抛异常、不在第一段失败时提前返回：先收集全部段，调用方再按
 * “不可行优先于未核验”决定拒绝、降级或标记 PARTIAL。</p>
 */
public final class ItineraryTravelChecker {

    public static final Set<String> TRANSPORTS = Set.of("WALK", "RIDE", "DRIVE", "TAXI", "TRANSIT");
    /** 最多向 provider 查询的站间段数；超过部分必须标未核验 */
    public static final int MAX_SEGMENT_CHECKS = 8;

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public enum Status {
        FEASIBLE,
        INFEASIBLE,
        UNVERIFIED
    }

    public enum UnverifiedReason {
        /** 缺起止时间/坐标，无法校验 */
        MISSING_DATA,
        /** provider 调用失败 */
        PROVIDER_ERROR,
        /** 超过最大校验段数 */
        CAP_EXCEEDED
    }

    @Data
    public static class Leg {
        /** 1-based 站点序号 */
        private int seq;
        private String name;
        private Double longitude;
        private Double latitude;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        /** 前往该站的交通方式；空/非法按 WALK */
        private String transportMode;
    }

    @Data
    public static class Segment {
        private int fromSeq;
        private int toSeq;
        private String transportMode;
        private Double distanceMeters;
        private Integer durationMinutes;
    }

    @Data
    public static class Violation {
        private int fromSeq;
        private int toSeq;
        private String fromName;
        private String toName;
        private String transportMode;
        private long availableMinutes;
        private int requiredMinutes;
        private LocalDateTime earliestStart;
    }

    @Data
    public static class Unverified {
        private int fromSeq;
        private int toSeq;
        private UnverifiedReason reason;
        private String detail;
        /** provider 异常时保留原始业务码，供 AI 路径复用 */
        private Integer code;
    }

    @Data
    public static class Result {
        private List<Segment> segments = new ArrayList<>();
        private List<Violation> violations = new ArrayList<>();
        private List<Unverified> unverified = new ArrayList<>();
        private int totalMinutes;
        private boolean anyEstimated;

        /** 所有段都实际校验过（没有缺字段、provider 异常或超段） */
        public boolean fullyVerified() {
            return unverified.isEmpty();
        }

        public boolean hasProviderError() {
            return unverified.stream().anyMatch(u -> u.getReason() == UnverifiedReason.PROVIDER_ERROR);
        }
    }

    private final TravelInfoProvider travelInfoProvider;

    public ItineraryTravelChecker(TravelInfoProvider travelInfoProvider) {
        this.travelInfoProvider = travelInfoProvider;
    }

    public Result check(List<Leg> legs) {
        Result result = new Result();
        if (legs == null || legs.size() < 2) {
            return result;
        }
        int checked = 0;
        for (int i = 0; i < legs.size() - 1; i++) {
            Leg current = legs.get(i);
            Leg next = legs.get(i + 1);
            if (checked >= MAX_SEGMENT_CHECKS) {
                result.getUnverified().add(unverified(current, next, UnverifiedReason.CAP_EXCEEDED,
                        "超过单次最多校验 " + MAX_SEGMENT_CHECKS + " 段的限制", null));
                break;
            }
            checked++;
            if (current.getEndTime() == null || next.getStartTime() == null
                    || current.getLongitude() == null || current.getLatitude() == null
                    || next.getLongitude() == null || next.getLatitude() == null) {
                result.getUnverified().add(unverified(current, next, UnverifiedReason.MISSING_DATA,
                        "缺少站点时间或坐标，未做交通校验", null));
                continue;
            }
            String mode = next.getTransportMode();
            if (mode == null || !TRANSPORTS.contains(mode)) {
                mode = "WALK";
            }
            RouteInfo route;
            try {
                route = travelInfoProvider.searchRoute(mode,
                        current.getLongitude(), current.getLatitude(),
                        next.getLongitude(), next.getLatitude());
            } catch (Exception ex) {
                Unverified item = unverified(current, next, UnverifiedReason.PROVIDER_ERROR,
                        ex.getMessage() == null ? "路线服务不可用" : ex.getMessage(), null);
                if (ex instanceof com.campus.common.exception.BusinessException businessException) {
                    item.setCode(businessException.getCode());
                }
                result.getUnverified().add(item);
                continue;
            }
            if (route == null || route.getDurationSeconds() == null) {
                result.getUnverified().add(unverified(current, next, UnverifiedReason.PROVIDER_ERROR,
                        "路线服务未返回预计耗时", null));
                continue;
            }
            long travelSeconds = Math.max(0, route.getDurationSeconds());
            long gapSeconds = Duration.between(current.getEndTime(), next.getStartTime()).getSeconds();
            long travelMinutes = (travelSeconds + 59) / 60;
            result.setAnyEstimated(result.isAnyEstimated()
                    || route.isEstimated() || "estimate".equals(route.getProvider()));

            Segment segment = new Segment();
            segment.setFromSeq(current.getSeq());
            segment.setToSeq(next.getSeq());
            segment.setTransportMode(mode);
            segment.setDistanceMeters(route.getDistanceMeters());
            segment.setDurationMinutes((int) travelMinutes);
            result.getSegments().add(segment);
            result.setTotalMinutes(result.getTotalMinutes() + (int) travelMinutes);

            if (gapSeconds < travelSeconds) {
                Violation violation = new Violation();
                violation.setFromSeq(current.getSeq());
                violation.setToSeq(next.getSeq());
                violation.setFromName(current.getName());
                violation.setToName(next.getName());
                violation.setTransportMode(mode);
                violation.setAvailableMinutes(Math.max(0, gapSeconds) / 60);
                violation.setRequiredMinutes((int) travelMinutes);
                violation.setEarliestStart(current.getEndTime().plusSeconds(travelSeconds));
                result.getViolations().add(violation);
            }
        }
        return result;
    }

    /** 两边文案同源：AI 校验错误与手动活动 400 使用同一句可执行提示 */
    public static String message(Violation violation) {
        return String.format(
                "「%s」→「%s」%s约 %d 分钟，但时间表只留了 %d 分钟；请把「%s」的开始时间设为 %s 或更晚"
                        + "（该段按目标站交通方式计算，未填写时按步行估算）",
                violation.getFromName(), violation.getToName(), transportLabel(violation.getTransportMode()),
                violation.getRequiredMinutes(), violation.getAvailableMinutes(),
                violation.getToName(), violation.getEarliestStart().format(HHMMSS));
    }

    public static String transportLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "WALK" -> "步行";
            case "RIDE" -> "骑行";
            case "DRIVE" -> "驾车";
            case "TAXI" -> "打车";
            case "TRANSIT" -> "公交";
            default -> "交通";
        };
    }

    private Unverified unverified(Leg current, Leg next, UnverifiedReason reason, String detail, Integer code) {
        Unverified item = new Unverified();
        item.setFromSeq(current.getSeq());
        item.setToSeq(next.getSeq());
        item.setReason(reason);
        item.setDetail(detail);
        item.setCode(code);
        return item;
    }
}
