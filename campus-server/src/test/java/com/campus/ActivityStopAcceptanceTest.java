package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 多站活动行程：创建/校验/编辑/详情/我的日程全链路 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActivityStopAcceptanceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("campus")
            .withUsername("campus")
            .withPassword("campus123")
            .withInitScript("db/test-init.sql");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    void multiStopCreateValidateUpdateAndSchedule() throws Exception {
        String creatorName = "stop_" + System.nanoTime();
        register(creatorName);
        TokenResponse tokens = login(creatorName, "password1");
        String auth = "Bearer " + tokens.getAccessToken();

        long placeId = createPlace(auth);
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        String startTime = TIME.format(start);
        String midTime = TIME.format(start.plusMinutes(60));
        String endTime = TIME.format(start.plusHours(3));

        // 1. 创建多站活动：站点1 坐标直存；站点2 关联地点
        String body = """
                {
                  "placeId": %d,
                  "title": "校园漫游",
                  "startTime": "%s",
                  "endTime": "%s",
                  "maxMembers": 5,
                  "stops": [
                    {"name": "图书馆集合", "longitude": 113.384100, "latitude": 23.045700,
                     "startTime": "%s", "endTime": "%s", "transportMode": "WALK", "note": "先到图书馆门口"},
                    {"name": "北区球场", "placeId": %d, "transportMode": "RIDE"}
                  ]
                }
                """.formatted(placeId, startTime, endTime, startTime, midTime, placeId);
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops.length()").value(2))
                .andExpect(jsonPath("$.data.stops[0].seq").value(1))
                .andExpect(jsonPath("$.data.stops[0].placeId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.stops[1].seq").value(2))
                .andExpect(jsonPath("$.data.stops[1].placeName").value("北区羽毛球场"))
                .andExpect(jsonPath("$.data.stops[1].longitude").isNotEmpty())
                .andReturn();
        long activityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2. 详情/我的日程都能取到行程
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops.length()").value(2));
        mockMvc.perform(get("/api/activities/mine?role=all").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].stops.length()").value(2));

        // 3. 校验失败路径
        // 3.1 站点时间早于活动开始
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "title": "x", "startTime": "%s", "endTime": "%s", "maxMembers": 5,
                                 "stops": [{"name": "a", "longitude": 113.38, "latitude": 23.04, "startTime": "%s"}]}
                                """.formatted(placeId, startTime, endTime,
                                TIME.format(start.minusMinutes(5)))))
                .andExpect(status().isBadRequest());
        // 3.2 站点时间倒序：第二站 startTime 严格早于第一站
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "title": "x", "startTime": "%s", "endTime": "%s", "maxMembers": 5,
                                 "stops": [{"name": "a", "longitude": 113.38, "latitude": 23.04, "startTime": "%s"},
                                           {"name": "b", "placeId": %d, "startTime": "%s"}]}
                                """.formatted(placeId, startTime, endTime, midTime, placeId, startTime)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("行程站点时间顺序不正确"));
        // 3.3 坐标与地点都缺
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "title": "x", "startTime": "%s", "endTime": "%s", "maxMembers": 5,
                                 "stops": [{"name": "只有名字"}]}
                                """.formatted(placeId, startTime, endTime)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("行程站点「只有名字」缺少地点或坐标"));
        // 3.4 超过 8 站
        StringBuilder manyStops = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            manyStops.append("{\"name\":\"s").append(i).append("\",\"longitude\":113.38,\"latitude\":23.04}");
            if (i < 8) {
                manyStops.append(",");
            }
        }
        manyStops.append("]");
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "title": "x", "startTime": "%s", "endTime": "%s", "maxMembers": 5,
                                 "stops": %s}
                                """.formatted(placeId, startTime, endTime, manyStops)))
                .andExpect(status().isBadRequest());

        // 4. 编辑整体替换行程
        mockMvc.perform(put("/api/activities/" + activityId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stops": [{"name": "改到东门", "longitude": 113.390000, "latitude": 23.048000}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops.length()").value(1))
                .andExpect(jsonPath("$.data.stops[0].name").value("改到东门"));

        // 5. 不带 stops 的普通活动兼容：stops 为空列表
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId": %d, "title": "普通活动", "startTime": "%s", "endTime": "%s", "maxMembers": 5}
                                """.formatted(placeId, startTime, endTime)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops.length()").value(0));
    }

    private long createPlace(String auth) throws Exception {
        String body = """
                {
                  "name": "北区羽毛球场",
                  "categoryId": 3,
                  "longitude": 113.4050000,
                  "latitude": 23.0550000,
                  "confirmNew": true
                }
                """;
        MvcResult result = mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        if (data.path("created").asBoolean()) {
            return data.path("place").path("id").asLong();
        }
        return data.path("candidates").path(0).path("id").asLong();
    }

    private void register(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname("测试");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private TokenResponse login(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new TokenResponse(
                data.path("accessToken").asText(),
                data.path("refreshToken").asText(),
                data.path("expiresIn").asLong()
        );
    }
}
