package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.plan.PlanValidationException;
import com.campus.ai.plan.PlanValidator;
import com.campus.ai.travel.PoiItem;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
import com.campus.map.service.SharedMapService;
import com.campus.place.service.PlaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 站点间交通校验的单测（纯构造，无 Spring）：
 * - 间隔不足时错误文案必须可执行（给出最早可设时间与交通方式口径，模型据此一次修正）；
 * - 按秒比较：修复"gap 向下取整、耗时向上取整"导致的连带误拒（9 分 40 秒间隔 / 9 分 38 秒耗时应通过）。
 */
class PlanValidatorTravelCheckTest {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final LocalDateTime START = LocalDateTime.now().plusHours(3).withNano(0);

    /** 固定耗时的 provider 假实现 */
    private TravelInfoProvider provider(long seconds, boolean estimated) {
        return new TravelInfoProvider() {
            @Override
            public RouteInfo searchRoute(String mode, double fromLng, double fromLat, double toLng, double toLat) {
                RouteInfo info = new RouteInfo();
                info.setMode(mode);
                info.setDistanceMeters(800.0);
                info.setDurationSeconds(seconds);
                info.setEstimated(estimated);
                info.setProvider(estimated ? "estimate" : "amap");
                return info;
            }

            @Override
            public List<PoiItem> searchPoi(String keyword, Double nearLng, Double nearLat, Integer radiusMeters) {
                return List.of();
            }
        };
    }

    private PlanValidator validator(TravelInfoProvider provider) {
        return new PlanValidator(new ObjectMapper(), mock(PlaceService.class), mock(SharedMapService.class), provider);
    }

    private PlanStop stop(String name, double lng, LocalDateTime start, LocalDateTime end, String mode) {
        PlanStop stop = new PlanStop();
        stop.setName(name);
        stop.setLongitude(lng);
        stop.setLatitude(23.05);
        stop.setStartTime(start);
        stop.setEndTime(end);
        stop.setTransportMode(mode);
        return stop;
    }

    /** A 站 30 分钟；B 站开始时间 = A.endTime + gapSeconds，B 停留 60 分钟 */
    private PlanDto planWithGapSeconds(long gapSeconds) {
        PlanDto plan = new PlanDto();
        plan.setTitle("交通校验");
        plan.setStartTime(START);
        plan.setEndTime(START.plusHours(4));
        LocalDateTime aEnd = START.plusMinutes(30);
        PlanStop a = stop("A 点", 113.38, START, aEnd, null);
        LocalDateTime bStart = aEnd.plusSeconds(gapSeconds);
        PlanStop b = stop("B 点", 113.39, bStart, bStart.plusMinutes(60), "WALK");
        plan.setStops(List.of(a, b));
        return plan;
    }

    @Test
    void gapShorterThanTravelIsRejectedWithActionableMessage() {
        // 真实步行 10 分钟、只留 8 分钟 → 拒绝；文案含"步行约 10 分钟"、"只留了 8 分钟"与最早可设时间
        String earliest = START.plusMinutes(30).plusSeconds(600).format(HHMMSS);
        assertThatThrownBy(() -> validator(provider(600L, false)).validate(planWithGapSeconds(8 * 60), 1L))
                .isInstanceOf(PlanValidationException.class)
                .hasMessageContaining("步行约 10 分钟")
                .hasMessageContaining("只留了 8 分钟")
                .hasMessageContaining("设为 " + earliest + " 或更晚")
                .hasMessageContaining("未填写时按步行估算")
                .satisfies(ex -> {
                    PlanValidationException pve = (PlanValidationException) ex;
                    assertThat(pve.getRuleId()).isEqualTo("TRAVEL_TIME_CONFLICT");
                    assertThat(pve.getDetails())
                            .containsEntry("availableMinutes", 8L)
                            .containsEntry("requiredMinutes", 10L)
                            .containsEntry("fromSeq", 1)
                            .containsEntry("toSeq", 2);
                });
    }

    @Test
    void exactTravelGapPassesAndSegmentsAreOverwritten() {
        PlanDto validated = validator(provider(600L, false)).validate(planWithGapSeconds(10 * 60), 1L);
        assertThat(validated.getRouteSegments()).hasSize(1);
        assertThat(validated.getRouteSegments().get(0).getTransportMode()).isEqualTo("WALK");
        assertThat(validated.getRouteSegments().get(0).getDurationMinutes()).isEqualTo(10);
        assertThat(validated.getTotalTravelMinutes()).isEqualTo(10);
        assertThat(validated.getRouteConfidence()).isEqualTo("AMAP");
    }

    @Test
    void subMinuteRoundingNoLongerRejectsFeasibleGap() {
        // 回归：真实 9 分 38 秒、间隔 9 分 40 秒应在旧实现下被误拒（floor(gap) 9 < ceil(travel) 10）
        PlanDto validated = validator(provider(578L, false)).validate(planWithGapSeconds(580), 1L);
        assertThat(validated.getRouteConfidence()).isEqualTo("AMAP");
        assertThat(validated.getTotalTravelMinutes()).isEqualTo(10);
    }
}
