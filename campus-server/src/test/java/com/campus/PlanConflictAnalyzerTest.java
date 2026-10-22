package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.planner.PlanConflictAnalyzer;
import com.campus.schedule.dto.BusyEntryDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同伴冲突归因纯单测：重叠、前后衔接不足、无冲突三类，并确认建议可执行、
 * 不把他人的日程标题带进输出（FREE_BUSY 隐私边界）。
 */
class PlanConflictAnalyzerTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 24, 14, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 10, 24, 16, 0);

    private PlanDto plan() {
        PlanDto plan = new PlanDto();
        plan.setStartTime(START);
        plan.setEndTime(END);
        return plan;
    }

    private BusyEntryDto busy(long userId, LocalDateTime start, LocalDateTime end) {
        BusyEntryDto entry = new BusyEntryDto();
        entry.setUserId(userId);
        entry.setStartTime(start);
        entry.setEndTime(end);
        entry.setSourceType("SCHEDULE");
        entry.setTitle("不应泄露的日程标题");
        return entry;
    }

    @Test
    void blockingConflictIsAttributedWithoutTitle() {
        List<PlanDto.PlanConflict> conflicts = PlanConflictAnalyzer.analyze(
                plan(), List.of(busy(2L, START.plusHours(1), END.plusMinutes(30))),
                Map.of(2L, "陈晨"));

        assertThat(conflicts).hasSize(1);
        PlanDto.PlanConflict conflict = conflicts.get(0);
        assertThat(conflict.getNickname()).isEqualTo("陈晨");
        assertThat(conflict.getSeverity()).isEqualTo("BLOCKING");
        assertThat(conflict.getOverlapMinutes()).isEqualTo(60);
        assertThat(conflict.getSuggestion()).contains("更换日期");
        assertThat(conflict.getSuggestion()).doesNotContain("不应泄露的日程标题");
    }

    @Test
    void adjacentBeforeSuggestsLaterStart() {
        List<PlanDto.PlanConflict> conflicts = PlanConflictAnalyzer.analyze(
                plan(), List.of(busy(2L, START.minusHours(1), START.minusMinutes(5))),
                Map.of(2L, "陈晨"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getSeverity()).isEqualTo("ADJACENT");
        assertThat(conflicts.get(0).getSuggestion()).contains("14:05");
    }

    @Test
    void adjacentAfterSuggestsEarlierEnd() {
        List<PlanDto.PlanConflict> conflicts = PlanConflictAnalyzer.analyze(
                plan(), List.of(busy(3L, END.plusMinutes(5), END.plusHours(1))),
                Map.of(3L, "王宁"));

        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).getSeverity()).isEqualTo("ADJACENT");
        assertThat(conflicts.get(0).getSuggestion()).contains("15:55");
    }

    @Test
    void busyOutsideWindowIsIgnored() {
        List<PlanDto.PlanConflict> conflicts = PlanConflictAnalyzer.analyze(
                plan(), List.of(busy(2L, START.minusHours(4), START.minusHours(3))),
                Map.of(2L, "陈晨"));

        assertThat(conflicts).isEmpty();
    }
}
