package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.campus.place.constant.PlaceRedisKey;
import com.campus.post.dto.CreatePostRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class EnhancementAcceptanceTest {

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void activityReportAdminCacheAndMap() throws Exception {
        String username = "enh_" + System.nanoTime();
        register(username);
        TokenResponse userTokens = login(username, "password1");
        String userAuth = "Bearer " + userTokens.getAccessToken();

        String otherName = "enh2_" + System.nanoTime();
        register(otherName);
        TokenResponse otherTokens = login(otherName, "password1");
        String otherAuth = "Bearer " + otherTokens.getAccessToken();
        long otherUserId = currentUserId(otherAuth);

        TokenResponse adminTokens = login("admin", "admin123");
        String adminAuth = "Bearer " + adminTokens.getAccessToken();

        mockMvc.perform(get("/api/admin/reports").header("Authorization", userAuth))
                .andExpect(status().isForbidden());

        long placeId = createPlace(userAuth);

        String activityBody = """
                {
                  "placeId": %d,
                  "title": "3v3 篮球约球",
                  "description": "今晚北区",
                  "startTime": "2026-12-01T20:00:00",
                  "endTime": "2026-12-01T22:00:00",
                  "maxMembers": 6
                }
                """.formatted(placeId);
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", userAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinedCount").value(1))
                .andExpect(jsonPath("$.data.phase").value("OPEN"));

        MvcResult activities = mockMvc.perform(get("/api/places/" + placeId + "/activities")
                        .header("Authorization", otherAuth))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(activities.getResponse().getContentAsString())
                .path("data").get(0).path("id").asLong();

        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", otherAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joinedCount").value(2))
                .andExpect(jsonPath("$.data.joined").value(true));

        CreatePostRequest postRequest = new CreatePostRequest();
        postRequest.setPlaceId(placeId);
        postRequest.setContent("垃圾广告");
        MvcResult postResult = mockMvc.perform(post("/api/posts")
                        .header("Authorization", otherAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postRequest)))
                .andExpect(status().isOk())
                .andReturn();
        long postId = objectMapper.readTree(postResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/places/" + placeId).header("Authorization", userAuth))
                .andExpect(status().isOk());
        assertThat(stringRedisTemplate.hasKey(PlaceRedisKey.detail(placeId))).isTrue();

        mockMvc.perform(get("/api/places/" + placeId + "/feed").header("Authorization", userAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activities[0].title").value("3v3 篮球约球"));

        mockMvc.perform(get("/api/places/map")
                        .header("Authorization", userAuth)
                        .param("minLng", "113.40")
                        .param("maxLng", "113.41")
                        .param("minLat", "23.05")
                        .param("maxLat", "23.06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("北区羽毛球场"));

        String reportBody = """
                {"targetType":"POST","targetId":%d,"reason":"广告/垃圾信息"}
                """.formatted(postId);
        MvcResult reportResult = mockMvc.perform(post("/api/reports")
                        .header("Authorization", userAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reportBody))
                .andExpect(status().isOk())
                .andReturn();
        long reportId = objectMapper.readTree(reportResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/admin/reports")
                        .header("Authorization", adminAuth)
                        .param("status", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(put("/api/admin/reports/" + reportId)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"action\":\"DELETE_CONTENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1));

        mockMvc.perform(get("/api/places/" + placeId + "/posts").header("Authorization", userAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        String userReport = """
                {"targetType":"USER","targetId":%d,"reason":"骚扰"}
                """.formatted(otherUserId);
        MvcResult userReportResult = mockMvc.perform(post("/api/reports")
                        .header("Authorization", userAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userReport))
                .andExpect(status().isOk())
                .andReturn();
        long userReportId = objectMapper.readTree(userReportResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(put("/api/admin/reports/" + userReportId)
                        .header("Authorization", adminAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\",\"action\":\"BAN_USER\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me").header("Authorization", otherAuth))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/admin/places/" + placeId).header("Authorization", adminAuth))
                .andExpect(status().isOk());
        assertThat(stringRedisTemplate.hasKey(PlaceRedisKey.detail(placeId))).isFalse();

        mockMvc.perform(get("/api/places/" + placeId).header("Authorization", userAuth))
                .andExpect(status().isNotFound());
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
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private long currentUserId(String auth) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/users/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
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
