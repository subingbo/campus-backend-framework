package com.campus;

import com.campus.ai.tool.AgentToolkit;
import com.campus.travel.TravelInfoProvider;
import com.campus.map.service.SharedMapService;
import com.campus.place.mapper.PlaceMapper;
import com.campus.schedule.service.ScheduleService;
import com.campus.ai.service.GroupMapContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 消融开关默认不改变工具集；no-planner / no-group-map 只影响评测，不关闭校验器。 */
class AgentToolkitAblationTest {

    @Test
    void defaultSchemaKeepsFullToolset() {
        AgentToolkit toolkit = toolkit();
        assertThat(names(toolkit)).contains("plan_itinerary", "get_group_map_context",
                "search_group_map_places", "get_place_context");
    }

    @Test
    void noPlannerRemovesOnlyPlanItinerary() {
        AgentToolkit toolkit = toolkit();
        ReflectionTestUtils.setField(toolkit, "ablation", "no-planner");
        assertThat(names(toolkit)).doesNotContain("plan_itinerary")
                .contains("get_group_map_context", "submit_plan");
        assertThat(toolkit.execute("plan_itinerary", "{}")).contains("已禁用");
    }

    @Test
    void noGroupMapRemovesGroupToolsButKeepsPlanner() {
        AgentToolkit toolkit = toolkit();
        ReflectionTestUtils.setField(toolkit, "ablation", "no-group-map");
        assertThat(names(toolkit))
                .doesNotContain("get_group_map_context", "search_group_map_places", "get_place_context",
                        "list_my_maps", "list_map_places")
                .contains("plan_itinerary", "submit_plan", "search_places", "amap_poi_search");
    }

    private AgentToolkit toolkit() {
        return new AgentToolkit(
                mock(SharedMapService.class),
                mock(PlaceMapper.class),
                mock(TravelInfoProvider.class),
                new ObjectMapper(),
                mock(ScheduleService.class),
                mock(GroupMapContextService.class));
    }

    @SuppressWarnings("unchecked")
    private List<String> names(AgentToolkit toolkit) {
        return toolkit.schemas().stream()
                .map(item -> {
                    Map<String, Object> function = (Map<String, Object>) item.get("function");
                    return String.valueOf(function.get("name"));
                })
                .toList();
    }
}
