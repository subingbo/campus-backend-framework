package com.campus.ai;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.dto.TraceDurationStat;
import com.campus.ai.trace.service.TraceStatsCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 耗时统计百分位与分组规则（确定性断言） */
class TraceStatsCalculatorTest {

    @Test
    void emptyAndNullInputYieldEmptyStats() {
        assertThat(TraceStatsCalculator.calculate(null)).isEmpty();
        assertThat(TraceStatsCalculator.calculate(List.of())).isEmpty();
    }

    @Test
    void singleSampleGetsSamePercentiles() {
        TraceDurationStat stat = TraceStatsCalculator.calculate(
                List.of(row("llm/response", null, 100L))).get(0);

        assertThat(stat.getType()).isEqualTo("llm/response");
        assertThat(stat.getTool()).isNull();
        assertThat(stat.getCount()).isEqualTo(1);
        assertThat(stat.getTotalMs()).isEqualTo(100L);
        assertThat(stat.getAvgMs()).isEqualTo(100L);
        assertThat(stat.getP50Ms()).isEqualTo(100L);
        assertThat(stat.getP95Ms()).isEqualTo(100L);
    }

    @Test
    void percentileUsesNearestRankOnSortedDurations() {
        TraceDurationStat stat = TraceStatsCalculator.calculate(List.of(
                row("llm/response", null, 400L),
                row("llm/response", null, 100L),
                row("llm/response", null, 300L),
                row("llm/response", null, 200L))).get(0);

        assertThat(stat.getCount()).isEqualTo(4);
        assertThat(stat.getTotalMs()).isEqualTo(1000L);
        assertThat(stat.getAvgMs()).isEqualTo(250L);
        assertThat(stat.getP50Ms()).isEqualTo(200L);
        assertThat(stat.getP95Ms()).isEqualTo(400L);
    }

    @Test
    void toolResultGroupsByToolNameAndOthersByType() {
        List<TraceDurationStat> stats = TraceStatsCalculator.calculate(List.of(
                row("tool/result", "route_info", 100L),
                row("tool/result", "route_info", 300L),
                row("tool/result", "list_my_maps", 50L),
                row("llm/response", null, 700L),
                row("tool/result", "route_info", null)));

        assertThat(stats).hasSize(3);
        TraceDurationStat route = stats.stream()
                .filter(s -> "route_info".equals(s.getTool())).findFirst().orElseThrow();
        assertThat(route.getType()).isEqualTo("tool/result");
        assertThat(route.getCount()).isEqualTo(2);
        assertThat(route.getP50Ms()).isEqualTo(100L);
        assertThat(route.getP95Ms()).isEqualTo(300L);
        assertThat(stats.stream()
                .filter(s -> "llm/response".equals(s.getType()))
                .findFirst())
                .isPresent()
                .hasValueSatisfying(s -> assertThat(s.getTool()).isNull());
    }

    private AgentTraceEvent row(String type, String tool, Long durationMs) {
        AgentTraceEvent event = new AgentTraceEvent();
        event.setEventType(type);
        event.setToolName(tool);
        event.setDurationMs(durationMs);
        return event;
    }
}
