package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.plan.PlanSource;
import com.campus.ai.service.GroupMapContextService;
import com.campus.ai.tool.AgentToolkit;
import com.campus.travel.TravelInfoProvider;
import com.campus.map.dto.MapDetailResponse;
import com.campus.map.dto.MapMemberResponse;
import com.campus.map.service.SharedMapService;
import com.campus.place.mapper.PlaceMapper;
import com.campus.schedule.dto.BusyEntryDto;
import com.campus.schedule.dto.GroupBusyDto;
import com.campus.schedule.service.ScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 群计划盖章单测：成员状态、冲突归因与 constraintStatus 由服务端真实数据决定，模型不可伪造。 */
class AgentPlanConflictStampTest {

    private static final long VIEWER_ID = 1L;
    private static final long GROUP_ID = 9L;
    private static final long MAP_ID = 51L;

    @Test
    void stampsMembersAndConflictsInGroupContext() {
        SharedMapService sharedMapService = mock(SharedMapService.class);
        PlaceMapper placeMapper = mock(PlaceMapper.class);
        TravelInfoProvider travelInfoProvider = mock(TravelInfoProvider.class);
        ScheduleService scheduleService = mock(ScheduleService.class);
        GroupMapContextService groupMapContextService = mock(GroupMapContextService.class);
        AgentToolkit toolkit = new AgentToolkit(sharedMapService, placeMapper, travelInfoProvider,
                new ObjectMapper(), scheduleService, groupMapContextService);

        when(groupMapContextService.groupMapIds(VIEWER_ID, GROUP_ID)).thenReturn(List.of(MAP_ID));
        MapDetailResponse detail = new MapDetailResponse();
        detail.setId(MAP_ID);
        detail.setGroupId(GROUP_ID);
        detail.setMembers(List.of(member(VIEWER_ID, "林晓", "OWNER"),
                member(2L, "陈晨", "EDITOR"), member(3L, "王宁", "EDITOR")));
        when(sharedMapService.getMapDetailAs(MAP_ID, VIEWER_ID)).thenReturn(detail);
        when(scheduleService.canSeeFreeBusy(eq(2L), eq("GROUP"), eq(GROUP_ID))).thenReturn(true);
        when(scheduleService.canSeeFreeBusy(eq(3L), eq("GROUP"), eq(GROUP_ID))).thenReturn(false);

        BusyEntryDto busy = new BusyEntryDto();
        busy.setUserId(2L);
        busy.setStartTime(LocalDateTime.of(2026, 10, 24, 15, 0));
        busy.setEndTime(LocalDateTime.of(2026, 10, 24, 16, 30));
        busy.setSourceType("SCHEDULE");
        busy.setTitle("不应泄露的日程标题");
        GroupBusyDto groupBusy = new GroupBusyDto();
        groupBusy.getKnown().add(busy);
        when(scheduleService.groupBusy(eq(VIEWER_ID), eq(GROUP_ID), any(), any())).thenReturn(groupBusy);

        PlanDto plan = new PlanDto();
        plan.setStartTime(LocalDateTime.of(2026, 10, 24, 14, 0));
        plan.setEndTime(LocalDateTime.of(2026, 10, 24, 16, 0));

        AgentToolkit.setGroupContext(VIEWER_ID, GROUP_ID);
        try {
            toolkit.stampGroupPlanContext(plan);
        } finally {
            AgentToolkit.clearGroupContext();
        }

        assertThat(plan.getGroupId()).isEqualTo(GROUP_ID);
        assertThat(plan.getMembers()).hasSize(3);
        assertThat(plan.getMembers()).filteredOn((m) -> m.getStatus().equals("UNKNOWN")).hasSize(1);
        assertThat(plan.getConflicts()).hasSize(1);
        assertThat(plan.getConflicts().get(0).getNickname()).isEqualTo("陈晨");
        assertThat(plan.getConflicts().get(0).getSuggestion()).doesNotContain("不应泄露的日程标题");
        assertThat(plan.getConstraintStatus()).isEqualTo("CONFLICT");
        assertThat(plan.getWarnings()).anyMatch((w) -> w.contains("1 位成员未授权"));
    }

    @Test
    void keepsUserLocationSourceWhenStampingGroupContext() {
        SharedMapService sharedMapService = mock(SharedMapService.class);
        PlaceMapper placeMapper = mock(PlaceMapper.class);
        TravelInfoProvider travelInfoProvider = mock(TravelInfoProvider.class);
        ScheduleService scheduleService = mock(ScheduleService.class);
        GroupMapContextService groupMapContextService = mock(GroupMapContextService.class);
        AgentToolkit toolkit = new AgentToolkit(sharedMapService, placeMapper, travelInfoProvider,
                new ObjectMapper(), scheduleService, groupMapContextService);

        when(groupMapContextService.groupMapIds(VIEWER_ID, GROUP_ID)).thenReturn(List.of(MAP_ID));
        when(groupMapContextService.placesInMaps(eq(List.of(MAP_ID)), any())).thenReturn(List.of());
        MapDetailResponse detail = new MapDetailResponse();
        detail.setId(MAP_ID);
        detail.setGroupId(GROUP_ID);
        detail.setMembers(List.of(member(VIEWER_ID, "林晓", "OWNER")));
        when(sharedMapService.getMapDetailAs(MAP_ID, VIEWER_ID)).thenReturn(detail);
        when(scheduleService.groupBusy(eq(VIEWER_ID), eq(GROUP_ID), any(), any()))
                .thenReturn(new GroupBusyDto());

        PlanDto plan = new PlanDto();
        plan.setStartTime(LocalDateTime.of(2026, 10, 24, 14, 0));
        plan.setEndTime(LocalDateTime.of(2026, 10, 24, 16, 0));
        PlanStop origin = new PlanStop();
        origin.setName("我的位置");
        origin.setSource(PlanSource.USER_LOCATION);
        origin.setLongitude(113.38);
        origin.setLatitude(23.04);
        PlanStop venue = new PlanStop();
        venue.setName("真实场馆");
        venue.setPlaceId(100L);
        plan.setStops(List.of(origin, venue));

        AgentToolkit.setGroupContext(VIEWER_ID, GROUP_ID);
        try {
            toolkit.stampGroupPlanContext(plan);
        } finally {
            AgentToolkit.clearGroupContext();
        }

        assertThat(plan.getStops().get(0).getSource()).isEqualTo(PlanSource.USER_LOCATION);
        assertThat(plan.getStops().get(1).getSource()).isEqualTo(PlanSource.PERSONAL_MAP);
    }

    private MapMemberResponse member(long userId, String nickname, String role) {
        MapMemberResponse member = new MapMemberResponse();
        member.setUserId(userId);
        member.setUsername("user" + userId);
        member.setNickname(nickname);
        member.setRole(role);
        return member;
    }
}
