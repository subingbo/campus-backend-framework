package com.campus;

import com.campus.ai.planner.PlannerModels;
import com.campus.ai.planner.SpatiotemporalPlanner;
import com.campus.travel.RouteInfo;
import com.campus.travel.TravelInfoProvider;
import com.campus.schedule.dto.BusyEntryDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.5 确定性时空联合求解纯单测：固定 TravelInfoProvider 假实现（步行 25 分钟半径参数化），
 * 验证开发计划文档中的算例——90+60 停留 + 25 步行 = 175 分钟占满 180 分钟窗口时，
 * 收尾缓冲不足必须判为不可行（LLM 不参与该判断）。
 */
class SpatiotemporalPlannerTest {

    private static final LocalDateTime DAY0 = LocalDateTime.of(2026, 10, 24, 14, 0);
    private static final LocalDateTime DAY0_END = LocalDateTime.of(2026, 10, 24, 17, 0);

    /** 固定耗时假实现：任意两点间时长=secondsPerPair，estimated=true（与 provider 估算退化语义一致） */
    private TravelInfoProvider walkingProvider(long secondsPerPair) {
        return new TravelInfoProvider() {
            @Override
            public RouteInfo searchRoute(String mode, double fromLng, double fromLat,
                                         double toLng, double toLat) {
                RouteInfo info = new RouteInfo();
                info.setMode(mode);
                info.setDistanceMeters(1000.0);
                info.setDurationSeconds(secondsPerPair);
                info.setEstimated(true);
                info.setProvider("estimate");
                return info;
            }

            @Override
            public java.util.List<com.campus.travel.PoiItem> searchPoi(String keyword, Double nearLng,
                                                                          Double nearLat, Integer radiusMeters) {
                return java.util.List.of();
            }
        };
    }

    private BusyEntryDto busy(LocalDateTime start, LocalDateTime end) {
        BusyEntryDto entry = new BusyEntryDto();
        entry.setUserId(1L);
        entry.setStartTime(start);
        entry.setEndTime(end);
        entry.setSourceType("SCHEDULE");
        return entry;
    }

    private PlannerModels.PlannerPlace place(long id, String name, long stay, String source, int rank) {
        return new PlannerModels.PlannerPlace(id, name, 113.370D + id * 0.01, 23.100D + id * 0.01,
                stay, source, source.equals("GROUP_MAP") ? 11L : null, rank);
    }

    @Test
    void docExample175Of180WithBufferFailsBut25MinuteBufferPasses() {
        // 文档例子：图书馆 90 + 步行 25 + 咖啡店 60 = 175 分钟，窗口 14:00-17:00=180 分钟，缺省缓冲 10 → 不可行
        PlannerModels.PlannerOutcome tight = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 90, "GROUP_MAP", 0), place(2, "咖啡店", 60, "GROUP_MAP", 1)),
                "WALK", SpatiotemporalPlanner.DEFAULT_BUFFER_MINUTES, SpatiotemporalPlanner.DEFAULT_BUFFER_MINUTES, walkingProvider(25 * 60));
        assertThat(tight.solutions()).isEmpty();
        assertThat(tight.suggestions()).isNotEmpty();

        // 用户把缓冲收紧或窗口加大 → 可行并给出两站时间线
        PlannerModels.PlannerOutcome ok = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 90, "GROUP_MAP", 0), place(2, "咖啡店", 60, "GROUP_MAP", 1)),
                "WALK", 5, 5, walkingProvider(25 * 60));
        assertThat(ok.solutions()).isNotEmpty();
        PlannerModels.PlannerSolution best = ok.solutions().get(0);
        assertThat(best.stops()).hasSize(2);
        // 14:00 →90→ 15:30 →25交通→ 15:55 →60→ 16:55，缓冲 5
        assertThat(best.stops().get(0).startTime()).isEqualTo(DAY0.withMinute(0));
        assertThat(best.stops().get(0).endTime()).isEqualTo(DAY0.plusMinutes(90));
        assertThat(best.stops().get(1).startTime()).isEqualTo(DAY0.plusMinutes(115));
        assertThat(best.stops().get(1).endTime()).isEqualTo(DAY0.plusMinutes(175));
        assertThat(best.totalTravelMinutes()).isEqualTo(25);
        assertThat(best.bufferRemainingMinutes()).isEqualTo(5);
    }

    @Test
    void roundTripWithOriginAppendsOutboundAndReturnLegs() {
        // 起点 → 图书馆(90 分钟停留) → 返回起点；每段 15 分钟；窗口 14:00-17:00，缓冲 10
        PlannerModels.PlannerOutcome outcome = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 90, "GROUP_MAP", 0)),
                "WALK", 10, 10, walkingProvider(15 * 60),
                new PlannerModels.OriginPoint("出发地", 113.36D, 23.09D), true);
        assertThat(outcome.solutions()).isNotEmpty();
        PlannerModels.PlannerSolution best = outcome.solutions().get(0);
        // 起点站 + 图书馆 + 返回站
        assertThat(best.stops()).hasSize(3);
        assertThat(best.stops().get(0).name()).isEqualTo("出发地");
        assertThat(best.stops().get(0).startTime()).isEqualTo(DAY0);
        assertThat(best.stops().get(0).endTime()).isEqualTo(DAY0.plusMinutes(15));
        assertThat(best.stops().get(1).startTime()).isEqualTo(DAY0.plusMinutes(15));
        assertThat(best.stops().get(1).endTime()).isEqualTo(DAY0.plusMinutes(105));
        assertThat(best.stops().get(2).name()).contains("返回");
        assertThat(best.stops().get(2).startTime()).isEqualTo(DAY0.plusMinutes(120));
        // 两段交通（去 + 回）都计入总耗时与段列表
        assertThat(best.totalTravelMinutes()).isEqualTo(30);
        assertThat(best.segments()).hasSize(2);
        assertThat(best.segments().get(1).fromIndex()).isEqualTo(1);
        assertThat(best.segments().get(1).toIndex()).isEqualTo(2);
    }

    @Test
    void busyIntervalsSplitWindowAndSolutionAvoidsThem() {
        // 15:00-15:30 有日程 → 空闲窗 15:30-17:00，90 分钟不够两站（175） → 无解建议
        PlannerModels.PlannerOutcome split = SpatiotemporalPlanner.plan(
                List.of(busy(DAY0.plusMinutes(60), DAY0.plusMinutes(90))), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 90, "GROUP_MAP", 0), place(2, "咖啡店", 60, "GROUP_MAP", 1)),
                "WALK", 0, 0, walkingProvider(25 * 60));
        assertThat(split.solutions()).isEmpty();
        assertThat(split.suggestions().get(0)).contains("共同空闲");

        // 单站的话可以放进 15:30 后的 90 分钟窗
        PlannerModels.PlannerOutcome single = SpatiotemporalPlanner.plan(
                List.of(busy(DAY0.plusMinutes(60), DAY0.plusMinutes(90))), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 80, "GROUP_MAP", 0)),
                "WALK", 0, 0, walkingProvider(25 * 60));
        assertThat(single.solutions()).hasSize(1);
        assertThat(single.solutions().get(0).stops().get(0).startTime())
                .isEqualTo(DAY0.plusMinutes(90));
    }

    @Test
    void groupMapPreferenceWinsOverPersonalWhenScoreTied() {
        // 两个等价的单站方案：GROUP_MAP 版本应排在前（固定权重 30 分共建造）
        PlannerModels.PlannerOutcome outcome = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆A", 60, "GROUP_MAP", 0), place(2, "图书馆B", 60, "PERSONAL_MAP", 1)),
                "WALK", 10, 10, walkingProvider(3 * 60));
        assertThat(outcome.solutions()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(outcome.solutions().get(0).groupMapStops()).isEqualTo(1);
        assertThat(outcome.solutions().get(0).stops().get(0).name()).isEqualTo("图书馆A");
    }

    @Test
    void unsupportedModeAndEmptyCandidatesRejected() {
        PlannerModels.PlannerOutcome flying = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 60, "GROUP_MAP", 0)),
                "FLY", 10, 10, walkingProvider(60));
        assertThat(flying.solutions()).isEmpty();
        assertThat(flying.suggestions().get(0)).contains("交通方式");

        PlannerModels.PlannerOutcome empty = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END, List.of(), "WALK", 10, 10, walkingProvider(60));
        assertThat(empty.solutions()).isEmpty();
        assertThat(empty.suggestions().get(0)).contains("候选地点");
    }

    @Test
    void taxiAndTransitModesAreSupported() {
        for (String mode : List.of("TAXI", "TRANSIT")) {
            PlannerModels.PlannerOutcome outcome = SpatiotemporalPlanner.plan(
                    new ArrayList<>(), DAY0, DAY0_END,
                    List.of(place(1, "图书馆", 60, "GROUP_MAP", 0)),
                    mode, 10, 10, walkingProvider(60));
            assertThat(outcome.solutions()).as("mode=%s", mode).isNotEmpty();
        }
    }

    @Test
    void windowTooShortReportsNeedVsAvailable() {
        // 需要停 600，窗口只有 3 小时
        PlannerModels.PlannerOutcome huge = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 600, "GROUP_MAP", 0)),
                "WALK", 10, 10, walkingProvider(60));
        assertThat(huge.solutions()).isEmpty();
        assertThat(huge.suggestions().get(0)).contains("600");
    }

    @Test
    void headBufferPushesChainPastAdjacentBusyEnd() {
        // 评审算例：14:00 上一段忙闲结束，活动窗口 14:00 起——求解器把链条推到 14:10 才开始（头部衔接缓冲）
        List<BusyEntryDto> busy = List.of(busy(DAY0.minusMinutes(60), DAY0));
        PlannerModels.PlannerOutcome outcome = SpatiotemporalPlanner.plan(
                busy, DAY0, DAY0_END.minusHours(1),
                List.of(place(1, "咖啡店", 60, "GROUP_MAP", 0)),
                "WALK", 10, 10, walkingProvider(3 * 60));
        assertThat(outcome.solutions()).hasSize(1);
        assertThat(outcome.solutions().get(0).stops().get(0).startTime())
                .isEqualTo(DAY0.plusMinutes(10));

        // 头部 + 收尾 + 停留挤掉整个窗口 → 无解
        PlannerModels.PlannerOutcome impossible = SpatiotemporalPlanner.plan(
                busy, DAY0, DAY0.plusMinutes(60),
                List.of(place(1, "咖啡店", 60, "GROUP_MAP", 0)),
                "WALK", 10, 10, walkingProvider(3 * 60));
        assertThat(impossible.solutions()).isEmpty();
        assertThat(impossible.suggestions().get(0)).contains("共同空闲");
    }

    @Test
    void routeConfidenceFlagsEstimatedAndVerifiedRoutes() {
        // provider 全部标注 estimated → ESTIMATED（不能宣称路线已验证）
        PlannerModels.PlannerOutcome estimated = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 60, "GROUP_MAP", 0), place(2, "咖啡店", 60, "GROUP_MAP", 1)),
                "WALK", 10, 10, walkingProvider(3 * 60));
        assertThat(estimated.solutions()).isNotEmpty();
        assertThat(estimated.solutions().get(0).routeConfidence()).isEqualTo("ESTIMATED");

        // provider 返回高德真实数据（estimated=false）→ AMAP
        TravelInfoProvider verified = new TravelInfoProvider() {
            @Override
            public RouteInfo searchRoute(String mode, double fromLng, double fromLat,
                                         double toLng, double toLat) {
                RouteInfo info = new RouteInfo();
                info.setMode(mode);
                info.setDistanceMeters(800.0);
                info.setDurationSeconds(300L);
                info.setPolyline(List.of());
                info.setEstimated(false);
                info.setProvider("amap");
                return info;
            }

            @Override
            public java.util.List<com.campus.travel.PoiItem> searchPoi(String keyword, Double nearLng,
                                                                          Double nearLat, Integer radiusMeters) {
                return java.util.List.of();
            }
        };
        PlannerModels.PlannerOutcome verifiedPlan = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 60, "GROUP_MAP", 0), place(2, "咖啡店", 60, "GROUP_MAP", 1)),
                "WALK", 10, 10, verified);
        assertThat(verifiedPlan.solutions().get(0).routeConfidence()).isEqualTo("AMAP");

        // 单站无交通段 → NONE
        PlannerModels.PlannerOutcome single = SpatiotemporalPlanner.plan(
                new ArrayList<>(), DAY0, DAY0_END,
                List.of(place(1, "图书馆", 60, "GROUP_MAP", 0)),
                "WALK", 10, 10, walkingProvider(60));
        assertThat(single.solutions().get(0).routeConfidence()).isEqualTo("NONE");
    }
}
