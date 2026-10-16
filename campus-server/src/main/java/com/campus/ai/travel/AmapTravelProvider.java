package com.campus.ai.travel;

import com.campus.ai.config.AmapProperties;
import com.campus.ai.constant.AiRedisKey;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.util.GeoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 高德 Web 服务适配器（本工程唯一直接调用高德的类；换厂商/移除厂商只改这里）。
 * 坐标契约：入参/出参/折线一律 GCJ-02，与 place、activity_stop 表同基准。
 * 错误矩阵：超时或网络故障 → 本地估算(estimated)；key 无效或配额话务类错误 → 明确报错不静默；
 * 无可行路线 → 抛错提示；POI 无结果 → 空列表。
 */
@Slf4j
@Component
public class AmapTravelProvider implements TravelInfoProvider {

    private static final Set<String> SUPPORTED_MODES = Set.of("WALK", "RIDE", "DRIVE");
    private static final String BASE_URL = "https://restapi.amap.com";
    /** 本地估算使用的平均速度 m/s：步行 ~1.25，骑行 ~3.3，驾车 ~8.3（校园/市区路况偏保守） */
    private static final Map<String, Double> ESTIMATE_SPEED = Map.of("WALK", 1.25, "RIDE", 3.3, "DRIVE", 8.3);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(6);
    /** v5 路径规划端点资源名与模式枚举不同名（walk → walking） */
    private static final Map<String, String> MODE_PATH =
            Map.of("WALK", "walking", "RIDE", "riding", "DRIVE", "driving");

    private final AmapProperties amapProperties;
    private final RouteCache routeCache;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AmapTravelProvider(AmapProperties amapProperties,
                              RouteCache routeCache,
                              @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.amapProperties = amapProperties;
        this.routeCache = routeCache;
        this.objectMapper = objectMapper;
        // 高德 v5 对 JDK HttpClient 的 h2 协商偶发 10017/RESOURCE_UNAVAILABLE，走 HTTP/1.1 稳定
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public RouteInfo searchRoute(String mode, double fromLng, double fromLat, double toLng, double toLat) {
        validateMode(mode);
        validateCoord("起点", fromLng, fromLat);
        validateCoord("终点", toLng, toLat);

        if (mode.equals("TRANSIT")) {
            // 公交整合路线需要城市区划码且 schema 复杂，本期明确不支持而非编造
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "暂不支持公交路线查询，请用步行/骑行/驾车");
        }

        String cacheKey = AiRedisKey.route(mode, fromLng, fromLat, toLng, toLat);
        RouteInfo cached = routeCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        if (amapProperties.getWebKey().isBlank()) {
            log.error("campus.amap.web-key 未配置，路线查询使用本地直线估算（非真实路径）");
            return estimate(mode, fromLng, fromLat, toLng, toLat);
        }

        RouteInfo info;
        try {
            info = callAmapRoute(mode, fromLng, fromLat, toLng, toLat);
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException ex) {
            log.warn("高德路线请求网络超时/失败，降级本地估算：{}", ex.toString());
            info = estimate(mode, fromLng, fromLat, toLng, toLat);
        } catch (Exception ex) {
            if (ex instanceof BusinessException business) {
                throw business;
            }
            log.warn("高德路线请求异常，降级本地估算：{}", ex.toString());
            info = estimate(mode, fromLng, fromLat, toLng, toLat);
        }
        routeCache.put(cacheKey, info);
        return info;
    }

    private RouteInfo callAmapRoute(String mode, double fromLng, double fromLat, double toLng, double toLat) throws Exception {
        String url = BASE_URL + "/v5/direction/" + MODE_PATH.get(mode)
                + "?origin=" + coord(fromLng) + "," + coord(fromLat)
                + "&destination=" + coord(toLng) + "," + coord(toLat)
                + "&show_fields=cost,polyline&key=" + amapProperties.getWebKey();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "campus-server")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        String status = root.path("status").asText("0");
        if (!status.equals("1")) {
            String infocode = root.path("infocode").asText("");
            String info = root.path("info").asText("");
            log.error("高德路线响应异常：infocode={} info={} body={}", infocode, info, abbreviateBody(response.body()));
            if (infocode.equals("10001") || infocode.equals("10003") || infocode.equals("10009")) {
                log.error("高德路线查询 key/配额问题：infocode={} info={}（请检查 campus.amap.web-key）", infocode, info);
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "路线查询服务暂时不可用");
            }
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "路线查询失败：" + info);
        }
        // v5 路径规划 2.0：总量汇总在 route.paths[0]，分段在 route.paths[0].steps（兼容旧结构做兜底）
        JsonNode routeNode = root.path("route");
        JsonNode paths = routeNode.path("paths");
        JsonNode firstPath = paths.isArray() && !paths.isEmpty() ? paths.get(0) : null;
        JsonNode steps = firstPath != null ? firstPath.path("steps") : routeNode.path("steps");
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "未找到可行路线");
        }
        double distance = firstPath != null ? firstPath.path("distance").asDouble(0) : 0;
        long duration = firstPath != null ? firstPath.path("cost").path("duration").asLong(0) : 0;
        List<double[]> polyline = new ArrayList<>();
        for (JsonNode step : steps) {
            if (distance <= 0) {
                distance += step.path("distance").asDouble(0);
            }
            if (duration <= 0) {
                duration += step.path("cost").path("duration").asLong(0);
            }
            appendPolyline(polyline, step.path("polyline").asText(""));
        }
        RouteInfo route = new RouteInfo();
        route.setMode(mode);
        route.setDistanceMeters(distance);
        route.setDurationSeconds(duration);
        route.setPolyline(polyline);
        route.setEstimated(false);
        route.setProvider("amap");
        return route;
    }

    @Override
    public List<PoiItem> searchPoi(String keyword, Double nearLng, Double nearLat, Integer radiusMeters) {
        if (keyword == null || keyword.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "POI 关键字不能为空");
        }
        if (amapProperties.getWebKey().isBlank()) {
            log.error("campus.amap.web-key 未配置，无法执行 POI 检索");
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索服务未配置");
        }
        String url = BASE_URL + "/v3/place/text?keywords=" + urlencode(keyword)
                + "&offset=6&page=1"
                + (nearLng != null && nearLat != null
                        ? "&location=" + coord(nearLng) + "," + coord(nearLat)
                        : "")
                + (radiusMeters != null ? "&radius=" + Math.min(Math.max(radiusMeters, 200), 50_000) : "")
                + "&key=" + amapProperties.getWebKey();
        try {
            JsonNode root = getJson(url);
            if (!root.path("status").asText("0").equals("1")) {
                String infocode = root.path("infocode").asText("");
                String info = root.path("info").asText("");
                if (infocode.equals("10001") || infocode.equals("10003") || infocode.equals("10009")) {
                    log.error("高德 POI 检索 key/配额问题：infocode={} info={}", infocode, info);
                    throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索服务暂时不可用");
                }
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索失败：" + info);
            }
            JsonNode pois = root.path("pois");
            List<PoiItem> result = new ArrayList<>();
            if (pois.isArray()) {
                for (JsonNode poi : pois) {
                    JsonNode location = poi.path("location");
                    String[] lngLat = location.asText("").split(",");
                    if (lngLat.length != 2) {
                        continue;
                    }
                    PoiItem item = new PoiItem();
                    item.setName(poi.path("name").asText(""));
                    item.setAddress(textOf(poi.path("address")));
                    item.setLongitude(Double.parseDouble(lngLat[0]));
                    item.setLatitude(Double.parseDouble(lngLat[1]));
                    String distance = poi.path("distance").asText("");
                    if (!distance.isBlank()) {
                        try {
                            item.setDistanceMeters(Double.parseDouble(distance));
                        } catch (NumberFormatException ignored) {
                            // 部分返回无距离
                        }
                    }
                    result.add(item);
                }
            }
            return result;
        } catch (java.net.http.HttpTimeoutException | java.net.ConnectException ex) {
            log.warn("高德 POI 请求网络超时/失败：{}", ex.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索请求超时，请稍后重试");
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("高德 POI 响应解析失败：{}", ex.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 响应解析失败");
        } catch (java.io.IOException ex) {
            log.warn("高德 POI 请求 IO 异常：{}", ex.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索失败，请稍后重试");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "POI 检索被中断");
        }
    }

    private JsonNode getJson(String url) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("User-Agent", "campus-server")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(response.body());
    }

    private RouteInfo estimate(String mode, double fromLng, double fromLat, double toLng, double toLat) {
        double meters = GeoUtils.distanceMeters(fromLng, fromLat, toLng, toLat);
        RouteInfo route = new RouteInfo();
        route.setMode(mode);
        route.setDistanceMeters(Math.round(meters) / 1.0);
        route.setDurationSeconds(Math.round(meters / ESTIMATE_SPEED.get(mode)));
        route.setPolyline(List.of());
        route.setEstimated(true);
        route.setProvider("estimate");
        return route;
    }

    private void appendPolyline(List<double[]> points, String polylineText) {
        for (String segment : polylineText.split(";")) {
            String lngLat = segment.trim();
            if (lngLat.isEmpty()) {
                continue;
            }
            String[] pair = lngLat.split(",");
            if (pair.length == 2) {
                points.add(new double[]{Double.parseDouble(pair[0]), Double.parseDouble(pair[1])});
            }
        }
    }

    private void validateMode(String mode) {
        if (!SUPPORTED_MODES.contains(mode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "交通方式不支持：" + mode);
        }
    }

    private void validateCoord(String label, double lng, double lat) {
        if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), label + "坐标无效");
        }
    }

    private String coord(double value) {
        return String.format("%.6f", value);
    }

    private String urlencode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String abbreviateBody(String text) {
        return text != null && text.length() > 300 ? text.substring(0, 300) + "..." : String.valueOf(text);
    }

    private String textOf(JsonNode node) {
        if (node.isArray()) {
            return node.isEmpty() ? "" : node.get(0).asText("");
        }
        return node.asText("");
    }
}
