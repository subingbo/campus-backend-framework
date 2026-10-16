package com.campus;

import com.campus.ai.planner.FreeWindow;
import com.campus.ai.planner.FreeWindowSolver;
import com.campus.schedule.dto.BusyEntryDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P1 求解器纯函数单测：计划 §5.2 例子 + 边界（相接合并/窗口裁剪/无空闲） */
class FreeWindowSolverTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 17, 0, 0);
    private static final LocalDateTime FROM = DAY.withHour(14);
    private static final LocalDateTime TO = DAY.withHour(20);
    private static final long NINETY = 90L;

    private static BusyEntryDto busy(LocalDateTime start, LocalDateTime end) {
        BusyEntryDto entry = new BusyEntryDto();
        entry.setStartTime(start);
        entry.setEndTime(end);
        entry.setSourceType("SCHEDULE");
        return entry;
    }

    @Test
    void planDocumentExampleThreeMembersYieldsThreeOneHourWindows() {
        // §5.2：A 15-16、B 17-18、C 19-20 → 空闲 14-15 / 16-17 / 18-19；90 分钟无解
        List<FreeWindow> free = FreeWindowSolver.findFree(List.of(
                busy(FROM.withHour(15), FROM.withHour(16)),
                busy(FROM.withHour(17), FROM.withHour(18)),
                busy(FROM.withHour(19), FROM.withHour(20))), FROM, TO, NINETY);
        assertThat(free).isEmpty();
        List<FreeWindow> sixty = FreeWindowSolver.findFree(List.of(
                busy(FROM.withHour(15), FROM.withHour(16)),
                busy(FROM.withHour(17), FROM.withHour(18)),
                busy(FROM.withHour(19), FROM.withHour(20))), FROM, TO, 60);
        assertThat(sixty).extracting(FreeWindow::start).containsExactly(
                FROM, DAY.withHour(16), DAY.withHour(18));
        assertThat(sixty).allSatisfy(w -> assertThat(w.durationMinutes()).isEqualTo(60));
    }

    @Test
    void overlappingAndAdjacentBusySegmentsMerge() {
        // 14-15.5、15.5-18、17-19 相接+重叠 → 空闲只剩 19:00-20:00（60 分钟）
        List<FreeWindow> free = FreeWindowSolver.findFree(List.of(
                busy(FROM, DAY.withHour(15).plusMinutes(30)),
                busy(DAY.withHour(15).plusMinutes(30), DAY.withHour(18)),
                busy(DAY.withHour(17), DAY.withHour(19))), FROM, TO, 60);
        assertThat(free).hasSize(1);
        assertThat(free.get(0).start()).isEqualTo(DAY.withHour(19));
        assertThat(free.get(0).end()).isEqualTo(TO);
        assertThat(free.get(0).durationMinutes()).isEqualTo(60);
        // 90 分钟则无解
        assertThat(FreeWindowSolver.findFree(List.of(
                busy(FROM, DAY.withHour(15).plusMinutes(30)),
                busy(DAY.withHour(15).plusMinutes(30), DAY.withHour(18)),
                busy(DAY.withHour(17), DAY.withHour(19))), FROM, TO, NINETY)).isEmpty();
    }

    @Test
    void busyOutsideWindowClippedAndEmptyBusyMeansWholeWindow() {
        // 窗外忙碌先裁剪到窗口内：10-15 的忙碌裁为 14-15 → 空闲=15:00-20:00 全段
        List<FreeWindow> free = FreeWindowSolver.findFree(
                List.of(busy(DAY.withHour(10), DAY.withHour(15))), FROM, TO, 60);
        assertThat(free).hasSize(1);
        assertThat(free.get(0).start()).isEqualTo(DAY.withHour(15));
        assertThat(free.get(0).durationMinutes()).isEqualTo(300);

        // 完全覆盖窗口的忙碌 → 无空闲
        assertThat(FreeWindowSolver.findFree(
                List.of(busy(DAY.withHour(10), DAY.withHour(23))), FROM, TO, 60)).isEmpty();

        // 无忙碌 → 整窗可用（350 ≤ 360 分钟）
        List<FreeWindow> all = FreeWindowSolver.findFree(List.of(), FROM, TO, 350);
        assertThat(all).hasSize(1);
        assertThat(all.get(0).durationMinutes()).isEqualTo(360);
    }
}
