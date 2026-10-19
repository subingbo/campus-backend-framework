package com.campus.ai;

import com.campus.ai.trace.security.TraceDataRedactor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 脱敏白名单单测：秘密、正文、坐标、地点名一律不得进入 data。 */
class TraceDataRedactorTest {

    private final TraceDataRedactor redactor = new TraceDataRedactor(new ObjectMapper());

    @Test
    void sanitizeDropsSensitiveKeysAndRedactsSecrets() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", "search_places");
        data.put("resultCount", 3);
        data.put("title", "校园漫步");
        data.put("name", "图书馆");
        data.put("note", "适合讨论");
        data.put("latitude", 23.04);
        data.put("longitude", 113.38);
        data.put("polyline", List.of(List.of(113.1, 23.1)));
        data.put("authorization", "Bearer abc.def.ghi");
        data.put("accessToken", "secret-token");
        data.put("apiKey", "sk-1234567890abcdef");
        data.put("message", "用户原始聊天");
        data.put("ruleIds", List.of("TRAVEL_TIME_CONFLICT"));
        data.put("estimated", true);
        data.put("details", Map.of("availableMinutes", 8, "requiredMinutes", 10));

        Map<String, Object> safe = redactor.sanitize(data);

        assertThat(safe).containsEntry("tool", "search_places")
                .containsEntry("resultCount", 3)
                .containsEntry("estimated", true);
        assertThat(safe).containsKey("ruleIds").containsKey("details");
        assertThat(safe.keySet()).doesNotContain("title", "name", "note", "latitude",
                "longitude", "polyline", "authorization", "accessToken", "apiKey", "message");
        assertThat(safe.toString()).doesNotContain("校园漫步", "图书馆", "适合讨论", "113.38",
                "Bearer", "sk-123", "secret-token");
    }

    @Test
    void sanitizeReplacesChineseFreeTextWithLength() {
        Map<String, Object> safe = redactor.sanitize(Map.of("reason", "这里有一个地点名"));
        assertThat(safe.get("reason")).isEqualTo("[text:8]");
    }

    @Test
    void routeToolInputNeverStoresCoordinates() {
        Map<String, Object> input = redactor.toolInput("route_info",
                "{\"mode\":\"WALK\",\"fromLng\":113.38,\"fromLat\":23.04,"
                        + "\"toLng\":113.39,\"toLat\":23.05}");
        assertThat(input).containsEntry("mode", "WALK");
        assertThat(input.toString()).doesNotContain("113.38", "23.04", "fromLng", "toLng");
    }

    @Test
    void locationToolOutputNeverStoresCoordinates() {
        Map<String, Object> output = redactor.toolOutput("get_my_location",
                "{\"available\":true,\"longitude\":113.3842,\"latitude\":23.0459}");
        assertThat(output).containsEntry("available", true);
        assertThat(output.toString()).doesNotContain("113.3842", "23.0459", "longitude", "latitude");
    }

    @Test
    void planInputOnlyStoresLengthsAndCounts() {
        Map<String, Object> input = redactor.toolInput("submit_plan",
                "{\"title\":\"校园漫步\",\"category\":\"OTHER\",\"maxMembers\":5,"
                        + "\"stops\":[{\"name\":\"图书馆\",\"longitude\":113.38,\"latitude\":23.04}]}");
        assertThat(input).containsEntry("category", "OTHER").containsEntry("maxMembers", 5L);
        assertThat(input.get("titleLength")).isEqualTo(4);
        assertThat(input.get("stopCount")).isEqualTo(1);
        assertThat(input.toString()).doesNotContain("校园漫步", "图书馆", "113.38");
    }

    @Test
    void businessEventsContainCountsNotPlaceText() {
        String result = """
                {"source":"GROUP_MAP","total":1,"results":[
                  {"placeId":101,"name":"图书馆","description":"适合自习","notes":[{"content":"安静"}]}
                ]}
                """;
        List<TraceDataRedactor.ToolEvent> events =
                redactor.businessEvents("search_group_map_places", result);
        assertThat(events).extracting(TraceDataRedactor.ToolEvent::type)
                .contains("map/search", "place/semantic-retrieval");
        String all = events.toString();
        assertThat(all).contains("GROUP_MAP").contains("resultCount=1")
                .doesNotContain("图书馆", "适合自习", "安静", "101");
    }

    @Test
    void classifyToolErrorUsesStableCodes() {
        assertThat(redactor.classifyToolError("{\"error\":\"该地点不在你可访问的共建地图上\"}"))
                .isEqualTo("PERMISSION_DENIED");
        assertThat(redactor.classifyToolError("{\"error\":\"POI 检索请求超时\"}"))
                .isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(redactor.classifyToolError("{\"error\":\"其他失败\"}"))
                .isEqualTo("TOOL_EXECUTION_ERROR");
    }
}
