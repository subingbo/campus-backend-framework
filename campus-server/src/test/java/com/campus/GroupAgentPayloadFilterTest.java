package com.campus;

import com.campus.ai.dto.GeoEvent;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.plan.PlanSource;
import com.campus.ai.service.GroupAgentPayloadFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 群聊 AGENT 文本/GEO 隐私过滤（纯函数，不依赖 Spring）。 */
class GroupAgentPayloadFilterTest {

    @Test
    void textReplacesPrivateStopNamesButKeepsPublicNames() {
        PlanDto plan = new PlanDto();
        PlanStop publicStop = new PlanStop();
        publicStop.setName("东区图书馆");
        publicStop.setSource(PlanSource.GROUP_MAP);
        PlanStop privateStop = new PlanStop();
        privateStop.setName("秘密基地");
        privateStop.setSource(PlanSource.PERSONAL_MAP);
        PlanStop origin = new PlanStop();
        origin.setName("我现在的位置");
        origin.setSource(PlanSource.USER_LOCATION);
        plan.setStops(List.of(publicStop, privateStop, origin));

        String filtered = GroupAgentPayloadFilter.filterText(
                "从东区图书馆出发，经过秘密基地，最后回到我现在的位置", plan);

        assertThat(filtered).contains("东区图书馆");
        assertThat(filtered).doesNotContain("秘密基地");
        assertThat(filtered).doesNotContain("我现在的位置");
        assertThat(filtered).contains("私人地点");
    }

    @Test
    void geoKeepsOnlyPublicSourcesAndDropsPrivateItinerary() {
        GeoEvent publicPlaces = placesGeo(List.of(
                place("东区图书馆", GeoEvent.SOURCE_GROUP_MAP, 113.1, 23.1),
                place("我的收藏", GeoEvent.SOURCE_PERSONAL_MAP, 113.2, 23.2),
                place("我的位置", GeoEvent.SOURCE_USER_LOCATION, 113.3, 23.3)
        ));
        GeoEvent privateItinerary = itineraryGeo(List.of(
                place("东区图书馆", GeoEvent.SOURCE_GROUP_MAP, 113.1, 23.1),
                place("秘密基地", GeoEvent.SOURCE_PERSONAL_MAP, 113.2, 23.2)
        ));
        GeoEvent publicItinerary = itineraryGeo(List.of(
                place("东区图书馆", GeoEvent.SOURCE_GROUP_MAP, 113.1, 23.1),
                place("南门咖啡", GeoEvent.SOURCE_GROUP_MAP, 113.15, 23.15)
        ));
        GeoEvent route = new GeoEvent();
        route.setType(GeoEvent.TYPE_ROUTE);
        route.setSource(GeoEvent.SOURCE_ROUTE);

        List<GeoEvent> filtered = GroupAgentPayloadFilter.filterGeoEvents(
                List.of(publicPlaces, privateItinerary, publicItinerary, route));

        assertThat(filtered).hasSize(2);
        GeoEvent keptPlaces = filtered.get(0);
        assertThat(keptPlaces.getPlaces()).extracting(GeoEvent.GeoPlace::getName)
                .containsExactly("东区图书馆");
        assertThat(keptPlaces.getPlaces()).extracting(GeoEvent.GeoPlace::getLongitude)
                .containsExactly(113.1);
        GeoEvent keptItinerary = filtered.get(1);
        assertThat(keptItinerary.getPlaces()).extracting(GeoEvent.GeoPlace::getName)
                .containsExactly("东区图书馆", "南门咖啡");
    }

    @Test
    void emptyOrNullInputIsSafe() {
        assertThat(GroupAgentPayloadFilter.filterGeoEvents(null)).isEmpty();
        assertThat(GroupAgentPayloadFilter.filterGeoEvents(List.of())).isEmpty();
        assertThat(GroupAgentPayloadFilter.filterText(null, null)).isNull();
        assertThat(GroupAgentPayloadFilter.filterText("没有方案", new PlanDto())).isEqualTo("没有方案");
    }

    private GeoEvent placesGeo(List<GeoEvent.GeoPlace> places) {
        GeoEvent event = new GeoEvent();
        event.setType(GeoEvent.TYPE_PLACES);
        event.setSource(GeoEvent.SOURCE_GROUP_MAP);
        event.setPlaces(places);
        return event;
    }

    private GeoEvent itineraryGeo(List<GeoEvent.GeoPlace> places) {
        GeoEvent event = new GeoEvent();
        event.setType(GeoEvent.TYPE_ITINERARY);
        event.setSource(GeoEvent.SOURCE_ROUTE);
        event.setPlaces(places);
        return event;
    }

    private GeoEvent.GeoPlace place(String name, String source, double lng, double lat) {
        GeoEvent.GeoPlace place = new GeoEvent.GeoPlace();
        place.setName(name);
        place.setSource(source);
        place.setLongitude(lng);
        place.setLatitude(lat);
        return place;
    }
}
