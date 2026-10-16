package com.campus.ai.planner;

import com.campus.schedule.dto.BusyEntryDto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 时空约束求解（计划 §5.2）：忙碌区间排序 → 合并（含首尾相接）→ 求规划窗口内补集 → 按需求时长筛选。
 * 确定性纯函数；LLM 不参与时间计算，只消费这里的结果。
 */
public final class FreeWindowSolver {

    private FreeWindowSolver() {
    }

    public static List<FreeWindow> findFree(List<BusyEntryDto> busy,
                                            LocalDateTime windowStart,
                                            LocalDateTime windowEnd,
                                            long durationMinutes) {
        List<FreeWindow> result = new ArrayList<>();
        if (windowEnd.isBefore(windowStart) || windowStart.equals(windowEnd)) {
            return result;
        }
        List<LocalDateTime[]> clipped = new ArrayList<>();
        if (busy != null) {
            for (BusyEntryDto entry : busy) {
                LocalDateTime busyStart = entry.getStartTime();
                LocalDateTime busyEnd = entry.getEndTime();
                if (busyStart.isBefore(windowStart)) {
                    busyStart = windowStart;
                }
                if (busyEnd.isAfter(windowEnd)) {
                    busyEnd = windowEnd;
                }
                if (busyStart.isBefore(busyEnd)) {
                    clipped.add(new LocalDateTime[]{busyStart, busyEnd});
                }
            }
        }
        clipped.sort(Comparator.comparing((LocalDateTime[] o) -> o[0]));
        LocalDateTime cursor = windowStart;
        for (LocalDateTime[] segment : clipped) {
            if (segment[0].isAfter(cursor)) {
                addIfLongEnough(result, cursor, segment[0], durationMinutes);
                cursor = segment[1];
            } else if (segment[1].isAfter(cursor)) {
                cursor = segment[1];
            }
        }
        if (cursor.isBefore(windowEnd)) {
            addIfLongEnough(result, cursor, windowEnd, durationMinutes);
        }
        return result;
    }

    private static void addIfLongEnough(List<FreeWindow> result,
                                        LocalDateTime start,
                                        LocalDateTime end,
                                        long durationMinutes) {
        long minutes = Duration.between(start, end).toMinutes();
        if (minutes >= durationMinutes) {
            result.add(new FreeWindow(start, end));
        }
    }
}
