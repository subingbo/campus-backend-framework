package com.campus.ai.planner;

import com.campus.ai.dto.PlanDto;
import com.campus.schedule.dto.BusyEntryDto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 群计划成员冲突分析：把忙碌区间按成员归因，判断与计划是重叠（BLOCKING）
 * 还是衔接不足（ADJACENT，默认 10 分钟），并给出可直接执行的调整建议。
 * 只输出时段与来源类型，不输出他人日程标题（FREE_BUSY 隐私边界）。
 */
public final class PlanConflictAnalyzer {

    /** 与前后安排的最小衔接间隔（分钟），与保存期重校验口径一致 */
    public static final int ADJACENT_MINUTES = 10;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private PlanConflictAnalyzer() {
    }

    public static List<PlanDto.PlanConflict> analyze(PlanDto plan, List<BusyEntryDto> busy,
                                                     Map<Long, String> nicknames) {
        if (plan == null || plan.getStartTime() == null || plan.getEndTime() == null || busy == null) {
            return List.of();
        }
        LocalDateTime planStart = plan.getStartTime();
        LocalDateTime planEnd = plan.getEndTime();
        List<PlanDto.PlanConflict> conflicts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BusyEntryDto entry : busy) {
            if (entry == null || entry.getUserId() == null
                    || entry.getStartTime() == null || entry.getEndTime() == null) {
                continue;
            }
            LocalDateTime busyStart = entry.getStartTime();
            LocalDateTime busyEnd = entry.getEndTime();
            String severity = severity(planStart, planEnd, busyStart, busyEnd);
            if (severity == null) {
                continue;
            }
            String key = entry.getUserId() + "|" + busyStart + "|" + busyEnd + "|" + severity;
            if (!seen.add(key)) {
                continue;
            }
            long overlapMinutes = 0;
            if ("BLOCKING".equals(severity)) {
                LocalDateTime overlapStart = busyStart.isAfter(planStart) ? busyStart : planStart;
                LocalDateTime overlapEnd = busyEnd.isBefore(planEnd) ? busyEnd : planEnd;
                overlapMinutes = Math.max(1, Duration.between(overlapStart, overlapEnd).toMinutes());
            }
            PlanDto.PlanConflict conflict = new PlanDto.PlanConflict();
            conflict.setUserId(entry.getUserId());
            conflict.setNickname(nicknames.getOrDefault(entry.getUserId(), "成员" + entry.getUserId()));
            conflict.setStartTime(busyStart);
            conflict.setEndTime(busyEnd);
            conflict.setSourceType(entry.getSourceType());
            conflict.setSeverity(severity);
            conflict.setOverlapMinutes((int) overlapMinutes);
            conflict.setSuggestion(suggestion(planStart, planEnd, busyStart, busyEnd, severity));
            conflicts.add(conflict);
        }
        conflicts.sort(Comparator.comparing(PlanDto.PlanConflict::getStartTime));
        return conflicts;
    }

    private static String severity(LocalDateTime planStart, LocalDateTime planEnd,
                                   LocalDateTime busyStart, LocalDateTime busyEnd) {
        LocalDateTime overlapStart = busyStart.isAfter(planStart) ? busyStart : planStart;
        LocalDateTime overlapEnd = busyEnd.isBefore(planEnd) ? busyEnd : planEnd;
        if (overlapEnd.isAfter(overlapStart)) {
            return "BLOCKING";
        }
        if (!busyEnd.isAfter(planStart)
                && Duration.between(busyEnd, planStart).toMinutes() < ADJACENT_MINUTES) {
            return "ADJACENT";
        }
        if (!busyStart.isBefore(planEnd)
                && Duration.between(planEnd, busyStart).toMinutes() < ADJACENT_MINUTES) {
            return "ADJACENT";
        }
        return null;
    }

    private static String suggestion(LocalDateTime planStart, LocalDateTime planEnd,
                                     LocalDateTime busyStart, LocalDateTime busyEnd, String severity) {
        if ("ADJACENT".equals(severity)) {
            if (!busyEnd.isAfter(planStart)) {
                long gap = Duration.between(busyEnd, planStart).toMinutes();
                return String.format("与前一项安排仅隔 %d 分钟，建议开始时间不早于 %s",
                        gap, HM.format(busyEnd.plusMinutes(ADJACENT_MINUTES)));
            }
            long gap = Duration.between(planEnd, busyStart).toMinutes();
            return String.format("与后一项安排仅隔 %d 分钟，建议结束时间不晚于 %s",
                    gap, HM.format(busyStart.minusMinutes(ADJACENT_MINUTES)));
        }
        if (!busyEnd.isAfter(planStart)) {
            return String.format("建议将活动开始时间调整到 %s 之后，或避开 %s-%s 时段",
                    HM.format(busyEnd.plusMinutes(ADJACENT_MINUTES)),
                    HM.format(busyStart), HM.format(busyEnd));
        }
        if (!busyStart.isBefore(planEnd)) {
            return String.format("建议将活动结束时间调整到 %s 之前，或避开 %s-%s 时段",
                    HM.format(busyStart.minusMinutes(ADJACENT_MINUTES)),
                    HM.format(busyStart), HM.format(busyEnd));
        }
        return String.format("该成员在 %s-%s 已有安排且与计划重叠，建议更换日期、缩短活动或另约时间",
                HM.format(busyStart), HM.format(busyEnd));
    }
}
