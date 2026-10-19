package com.campus.ai.trace.security;

import com.campus.ai.trace.domain.AgentEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 追踪数据脱敏与摘要白名单。
 *
 * <p>在事件入队之前执行：原始工具参数/结果/模型输出先被解析成 JsonNode，再只抽取
 * 计数、ID、枚举、布尔、耗时等安全字段。绝不保存聊天原文、地点名/地址/备注、
 * 精确经纬度/轨迹、JWT/API Key/Cookie。</p>
 *
 * <p>额外兜底：任何进入事件 data 的 Map 都会被 {@link #sanitize(Map)} 再过滤一次。</p>
 */
@Component
public class TraceDataRedactor {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final Set<String> SAFE_KEYS = Set.of(
            "tool", "source", "mode", "provider", "category", "status", "reason",
            "phase", "operation", "transport", "trigger", "model", "ruleId", "ruleIds",
            "ruleSet", "errorCode", "errorType", "httpStatus", "finishReason", "closeReason",
            "constraintStatus", "routeConfidence", "lastErrorCode", "toolNames",
            "details", "input", "output", "round", "rounds", "earliestStart",
            "hasDraft", "hasUserLocation", "hasOrigin", "returnToStart", "created", "complete",
            "estimated", "reused", "idempotentReuse", "inferred", "cancelled", "rolledBack",
            "passed", "changed", "truncated", "parseError", "serializationError", "newSession",
            "descriptionPresent", "keywordPresent", "hasNear", "hasReferenceMap",
            "hasSuggestions", "hasSourceMaps", "available", "kind",
            "estimatedSegments", "outcome", "stream", "historyLimit", "clientCancelled"
    );

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "name", "title", "content", "description", "note", "notes", "noteDigest",
            "address", "message", "keyword", "summary", "text", "prompt", "query",
            "polyline", "latitude", "longitude", "lng", "lat", "coordinates",
            "authorization", "cookie", "password", "apiKey", "secret", "token",
            "accessToken", "refreshToken"
    );

    private static final List<String> FORBIDDEN_SUFFIXES = List.of(
            "Name", "Title", "Content", "Description", "Note", "Notes", "Address",
            "Message", "Keyword", "Summary", "Text", "Prompt", "Query", "Polyline",
            "Latitude", "Longitude", "Lng", "Lat", "Token", "Secret", "Password",
            "Authorization", "Cookie"
    );

    private static final Pattern SAFE_KEY_PATTERN = Pattern.compile(
            "^(?:tool|source|mode|provider|category|status|reason|phase|operation|transport|trigger|model"
                    + "|ruleId|ruleIds|ruleSet|errorCode|errorType|httpStatus|finishReason|closeReason"
                    + "|constraintStatus|routeConfidence|lastErrorCode|toolNames|details|input|output"
                    + "|round|rounds|earliestStart|hasDraft|hasUserLocation|hasOrigin|returnToStart|created"
                    + "|complete|estimated|reused|idempotentReuse|inferred|cancelled|rolledBack|passed|changed"
                    + "|truncated|parseError|serializationError|newSession|descriptionPresent|keywordPresent"
                    + "|hasNear|hasReferenceMap|hasSuggestions|hasSourceMaps|available|kind|estimatedSegments"
                    + "|outcome|stream|historyLimit|clientCancelled"
                    + "|.*(?:Id|Ids|Count|Minutes|Ms|Length|Tokens|Version|Seq|Status|Code|Mode|Source"
                    + "|Provider|Reason|Type|Phase|Operation|Transport|AtMs|Meters))$");

    private static final Pattern SAFE_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_./:@+-]{1,80}$");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)^bearer\\s+.+$");
    private static final Pattern API_KEY_PATTERN = Pattern.compile("^sk-[A-Za-z0-9_-]{8,}$");
    private static final Pattern JWT_PATTERN = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\..+$");

    private static final Object SKIP = new Object();
    private static final int MAX_DEPTH = 4;
    private static final int MAX_LIST = 20;
    private static final int MAX_MAP = 40;

    private final ObjectMapper objectMapper;

    public TraceDataRedactor(@Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ---------- 通用白名单 ----------

    public Map<String, Object> sanitize(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = sanitizeMap(data, 0);
        return out.isEmpty() ? Map.of() : out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMap(Map<String, Object> data, int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (depth > MAX_DEPTH) {
            return out;
        }
        int count = 0;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (++count > MAX_MAP) {
                break;
            }
            String key = entry.getKey();
            if (!isSafeKey(key)) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), depth + 1);
            if (value != SKIP) {
                out.put(key, value);
            }
        }
        return out;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return safeString(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                if (!isSafeKey(key)) {
                    continue;
                }
                Object child = sanitizeValue(entry.getValue(), depth + 1);
                if (child != SKIP) {
                    nested.put(key, child);
                }
            }
            return nested;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>();
            for (Object item : collection) {
                if (list.size() >= MAX_LIST) {
                    break;
                }
                Object child = sanitizeValue(item, depth + 1);
                if (child != SKIP) {
                    list.add(child);
                }
            }
            return list;
        }
        return SKIP;
    }

    private boolean isSafeKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (FORBIDDEN_KEYS.contains(key)) {
            return false;
        }
        for (String suffix : FORBIDDEN_SUFFIXES) {
            if (key.endsWith(suffix)) {
                return false;
            }
        }
        return SAFE_KEYS.contains(key) || SAFE_KEY_PATTERN.matcher(key).matches();
    }

    private Object safeString(String value) {
        if (value == null) {
            return null;
        }
        if (isSecretLike(value)) {
            return "[REDACTED]";
        }
        if (value.length() > 80) {
            return "[text:" + value.length() + "]";
        }
        return SAFE_VALUE_PATTERN.matcher(value).matches() ? value : "[text:" + value.length() + "]";
    }

    private boolean isSecretLike(String value) {
        String trimmed = value.trim();
        return BEARER_PATTERN.matcher(trimmed).matches()
                || API_KEY_PATTERN.matcher(trimmed).matches()
                || JWT_PATTERN.matcher(trimmed).matches();
    }

    // ---------- 工具入参摘要 ----------

    public Map<String, Object> toolInput(String tool, String argsJson) {
        JsonNode node = parse(argsJson);
        if (node == null || !node.isObject()) {
            return mapOf("parseError", true, "length", argsJson == null ? 0 : argsJson.length());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (tool == null ? "" : tool) {
            case "list_map_places" -> put(out, "mapId", longField(node, "mapId"));
            case "search_places" -> put(out, "keywordLength", textLength(node, "keyword"));
            case "amap_poi_search" -> {
                put(out, "keywordLength", textLength(node, "keyword"));
                put(out, "hasNear", node.hasNonNull("nearLng") && node.hasNonNull("nearLat"));
                put(out, "radiusMeters", longField(node, "radiusMeters"));
            }
            case "route_info" -> put(out, "mode", textField(node, "mode"));
            case "get_my_schedule", "find_free_windows" -> {
                put(out, "windowMinutes", windowMinutes(node, "dateFrom", "dateTo"));
                put(out, "durationMinutes", longField(node, "durationMinutes"));
            }
            case "search_group_map_places" -> {
                put(out, "keywordLength", textLength(node, "keyword"));
                put(out, "categoryId", longField(node, "categoryId"));
                put(out, "limit", longField(node, "limit"));
            }
            case "get_place_context" -> {
                put(out, "placeId", longField(node, "placeId"));
                put(out, "hasMapId", node.hasNonNull("mapId"));
            }
            case "plan_itinerary" -> {
                put(out, "transportMode", textField(node, "transportMode"));
                put(out, "windowMinutes", windowMinutes(node, "dateFrom", "dateTo"));
                put(out, "hasOrigin", node.hasNonNull("originLng"));
                put(out, "returnToStart", boolField(node, "returnToStart"));
                put(out, "placeIdCount", arraySize(node, "placeIds"));
                put(out, "keywordPresent", node.hasNonNull("keyword"));
                put(out, "bufferMinutes", longField(node, "bufferMinutes"));
            }
            case "submit_plan" -> {
                put(out, "titleLength", textLength(node, "title"));
                put(out, "stopCount", arraySize(node, "stops"));
                put(out, "category", textField(node, "category"));
                put(out, "maxMembers", longField(node, "maxMembers"));
                put(out, "hasSourceMaps", node.hasNonNull("sourceMapIds"));
                put(out, "hasReferenceMap", node.hasNonNull("referenceMapId"));
            }
            default -> out.put("keyCount", node.size());
        }
        return out.isEmpty() ? Map.of("keyCount", node.size()) : out;
    }

    // ---------- 工具结果摘要 ----------

    public Map<String, Object> toolOutput(String tool, String resultJson) {
        JsonNode node = parse(resultJson);
        if (node == null) {
            return Map.of("parseError", true, "length", resultJson == null ? 0 : resultJson.length());
        }
        if (node.isArray()) {
            return arrayOutput(tool, node);
        }
        if (!node.isObject()) {
            return Map.of("textLength", node.isTextual() ? node.asText().length() : 0);
        }
        if (node.hasNonNull("error")) {
            return Map.of("error", true);
        }
        return objectOutput(tool, node);
    }

    private Map<String, Object> arrayOutput(String tool, JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (tool == null ? "" : tool) {
            case "list_my_maps" -> out.put("mapCount", node.size());
            case "list_map_places" -> out.put("placeCount", node.size());
            case "search_places" -> {
                out.put("resultCount", node.size());
                out.put("placeIds", firstIds(node, 10, "id"));
            }
            case "amap_poi_search" -> out.put("resultCount", node.size());
            default -> out.put("resultCount", node.size());
        }
        return out;
    }

    private Map<String, Object> objectOutput(String tool, JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (tool == null ? "" : tool) {
            case "get_group_map_context" -> {
                put(out, "source", textField(node, "source"));
                put(out, "mapCount", arraySize(node, "maps"));
            }
            case "search_group_map_places" -> {
                put(out, "source", textField(node, "source"));
                put(out, "total", intField(node, "total"));
                JsonNode results = node.get("results");
                put(out, "noteHitCount", noteHitCount(results));
                put(out, "distinctPlaceCount", results != null && results.isArray() ? results.size() : null);
            }
            case "get_place_context" -> {
                put(out, "source", textField(node, "source"));
                JsonNode place = node.get("place");
                if (place != null && place.isObject()) {
                    put(out, "placeId", longField(place, "placeId"));
                    put(out, "noteCount", arraySize(place, "notes"));
                    put(out, "descriptionPresent", place.hasNonNull("description"));
                }
            }
            case "plan_itinerary" -> {
                JsonNode solutions = node.get("solutions");
                put(out, "solutionCount", solutions != null && solutions.isArray() ? solutions.size() : 0);
                put(out, "unknownCount", intField(node, "unknownCount"));
                put(out, "hasOrigin", node.hasNonNull("origin"));
                put(out, "hasSuggestions", arraySize(node, "suggestions"));
                put(out, "windowMinutes", windowMinutes(node.path("window"), "start", "end"));
                if (solutions != null && solutions.isArray() && !solutions.isEmpty()) {
                    JsonNode first = solutions.get(0);
                    put(out, "stopCount", arraySize(first, "stops"));
                    put(out, "segmentCount", arraySize(first, "routeSegments"));
                    put(out, "totalTravelMinutes", intField(first, "totalTravelMinutes"));
                    put(out, "routeConfidence", textField(first, "routeConfidence"));
                    put(out, "estimatedSegments", estimatedSegments(first.get("routeSegments")));
                }
            }
            case "find_free_windows" -> {
                put(out, "source", textField(node, "source"));
                put(out, "durationMinutes", longField(node, "durationMinutes"));
                put(out, "windowCount", arraySize(node, "windows"));
                put(out, "windowsTruncated", boolField(node, "windowsTruncated"));
                put(out, "unknownCount", intField(node, "unknownCount"));
            }
            case "get_my_schedule" -> {
                put(out, "entryCount", arraySize(node, "entries"));
                put(out, "truncated", boolField(node, "truncated"));
            }
            case "get_my_location" -> put(out, "available", boolField(node, "available"));
            case "route_info" -> {
                put(out, "mode", textField(node, "mode"));
                put(out, "provider", textField(node, "provider"));
                put(out, "estimated", boolField(node, "estimated"));
                put(out, "durationMinutes", intField(node, "durationMinutes"));
                put(out, "distanceMeters", longField(node, "distanceMeters"));
            }
            case "submit_plan" -> put(out, "accepted", boolField(node, "accepted"));
            default -> out.put("parsed", true);
        }
        return out;
    }

    // ---------- 派生业务事件 ----------

    public record ToolEvent(String type, Map<String, Object> data) {
    }

    public List<ToolEvent> businessEvents(String tool, String resultJson) {
        JsonNode node = parse(resultJson);
        if (node == null || (!node.isObject() && !node.isArray())
                || (node.isObject() && node.hasNonNull("error"))) {
            return List.of();
        }
        String name = tool == null ? "" : tool;
        Map<String, Object> summary = toolOutput(name, resultJson);
        List<ToolEvent> events = new ArrayList<>();
        switch (name) {
            case "list_my_maps", "list_map_places", "search_places", "amap_poi_search",
                    "search_group_map_places", "get_group_map_context" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", name);
                data.put("source", mapSource(name, node));
                copyCount(data, summary, "resultCount", "total", "mapCount", "placeCount");
                events.add(new ToolEvent(AgentEventType.MAP_SEARCH, data));
                if ("search_group_map_places".equals(name)) {
                    Map<String, Object> place = new LinkedHashMap<>();
                    place.put("tool", name);
                    place.put("source", mapSource(name, node));
                    copy(place, summary, "total", "noteHitCount", "distinctPlaceCount");
                    events.add(new ToolEvent(AgentEventType.PLACE_SEMANTIC_RETRIEVAL, place));
                }
            }
            case "get_place_context" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", name);
                copy(data, summary, "source", "placeId", "noteCount", "descriptionPresent");
                events.add(new ToolEvent(AgentEventType.PLACE_SEMANTIC_RETRIEVAL, data));
            }
            case "route_info" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", name);
                copy(data, summary, "mode", "provider", "estimated", "durationMinutes", "distanceMeters");
                events.add(new ToolEvent(AgentEventType.ROUTE_ESTIMATE, data));
            }
            case "find_free_windows", "get_my_schedule" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("tool", name);
                data.put("kind", "busy".equals(name) ? "busy" : "free-window");
                copy(data, summary, "source", "durationMinutes", "windowCount",
                        "windowsTruncated", "unknownCount", "entryCount", "truncated");
                events.add(new ToolEvent(AgentEventType.SCHEDULE_FREE_WINDOW, data));
            }
            case "plan_itinerary" -> {
                Map<String, Object> schedule = new LinkedHashMap<>();
                schedule.put("tool", name);
                schedule.put("kind", "solver");
                copy(schedule, summary, "solutionCount", "unknownCount", "windowMinutes",
                        "stopCount", "totalTravelMinutes", "routeConfidence");
                events.add(new ToolEvent(AgentEventType.SCHEDULE_FREE_WINDOW, schedule));
                Map<String, Object> route = new LinkedHashMap<>();
                route.put("tool", name);
                copy(route, summary, "segmentCount", "totalTravelMinutes",
                        "estimatedSegments", "routeConfidence");
                events.add(new ToolEvent(AgentEventType.ROUTE_ESTIMATE, route));
            }
            default -> {
                // submit_plan 的 plan/draft、plan/validate 由调用方单独记录
            }
        }
        return events;
    }

    /** 工具错误分类：只返回稳定错误码，不回传原始 message */
    public String classifyToolError(String resultJson) {
        JsonNode node = parse(resultJson);
        if (node == null) {
            return "TOOL_EXECUTION_ERROR";
        }
        if (node.isObject() && node.hasNonNull("accepted") && !node.path("accepted").asBoolean(true)) {
            return "PLAN_REJECTED";
        }
        String message = "";
        if (node.isObject()) {
            message = node.path("error").asText(node.path("message").asText(""));
        } else if (node.isTextual()) {
            message = node.asText();
        }
        if (message.contains("权限") || message.contains("授权") || message.contains("可访问")
                || message.toLowerCase().contains("forbidden")) {
            return "PERMISSION_DENIED";
        }
        if (message.contains("不存在") || message.toLowerCase().contains("not found")) {
            return "NOT_FOUND";
        }
        if (message.contains("超时") || message.contains("不可用")
                || message.toLowerCase().contains("timeout")) {
            return "PROVIDER_UNAVAILABLE";
        }
        return "TOOL_EXECUTION_ERROR";
    }

    public boolean isPermissionDenied(String resultJson) {
        return "PERMISSION_DENIED".equals(classifyToolError(resultJson));
    }

    // ---------- helpers ----------

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    private String mapSource(String tool, JsonNode node) {
        return switch (tool == null ? "" : tool) {
            case "list_my_maps", "list_map_places" -> "PERSONAL_MAP";
            case "search_places" -> "CAMPUS_LIB";
            case "amap_poi_search" -> "AMAP_POI";
            case "search_group_map_places", "get_group_map_context" -> node.path("source").asText("GROUP_MAP");
            default -> "UNKNOWN";
        };
    }

    private int noteHitCount(JsonNode results) {
        if (results == null || !results.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode item : results) {
            JsonNode notes = item.get("notes");
            if (notes != null && notes.isArray() && !notes.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private int estimatedSegments(JsonNode segments) {
        if (segments == null || !segments.isArray()) {
            return 0;
        }
        int count = 0;
        for (JsonNode segment : segments) {
            if (segment.path("estimated").asBoolean(false)) {
                count++;
            }
        }
        return count;
    }

    private List<Long> firstIds(JsonNode array, int limit, String field) {
        List<Long> ids = new ArrayList<>();
        for (JsonNode item : array) {
            if (ids.size() >= limit) {
                break;
            }
            JsonNode id = item.get(field);
            if (id != null && id.isNumber()) {
                ids.add(id.asLong());
            }
        }
        return ids;
    }

    private Long windowMinutes(JsonNode node, String fromKey, String toKey) {
        String from = textField(node, fromKey);
        String to = textField(node, toKey);
        if (from == null || to == null) {
            return null;
        }
        try {
            return Duration.between(LocalDateTime.parse(from, TS), LocalDateTime.parse(to, TS)).toMinutes();
        } catch (Exception ex) {
            return null;
        }
    }

    private String textField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private Integer textLength(JsonNode node, String key) {
        String text = textField(node, key);
        return text == null ? null : text.length();
    }

    private Long longField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isNumber() ? value.asLong() : null;
    }

    private Integer intField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isNumber() ? value.asInt() : null;
    }

    private Boolean boolField(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private Integer arraySize(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isArray() ? value.size() : null;
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            put(target, key, source.get(key));
        }
    }

    private void copyCount(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            if (source.get(key) != null) {
                target.put("resultCount", source.get(key));
                return;
            }
        }
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}
