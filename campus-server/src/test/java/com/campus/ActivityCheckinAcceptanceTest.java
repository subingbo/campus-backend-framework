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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活动签到全链路验收：创建 → 报名 → 签到（对码/错码/窗口/重复）→ 成员记录 → 编辑/取消 → 日程与邀请。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActivityCheckinAcceptanceTest {

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
    void activityCheckinInviteAndSchedule() throws Exception {
        String creatorName = "act_" + System.nanoTime();
        register(creatorName);
        TokenResponse creatorTokens = login(creatorName, "password1");
        String creatorAuth = "Bearer " + creatorTokens.getAccessToken();

        String memberName = "mem_" + System.nanoTime();
        register(memberName);
        TokenResponse memberTokens = login(memberName, "password1");
        String memberAuth = "Bearer " + memberTokens.getAccessToken();
        long memberId = currentUserId(memberAuth);

        long placeId = createPlace(creatorAuth);

        // 1. 创建比赛类活动（开始时间在 10 分钟后，签到窗口 = 开始时间 + 15min，保证可以签到）
        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        String body = """
                {
                  "placeId": %d,
                  "title": "3v3 招新赛",
                  "description": "北区球场集合",
                  "startTime": "%s",
                  "endTime": "%s",
                  "maxMembers": 8,
                  "category": "COMPETITION"
                }
                """.formatted(placeId, TIME.format(start), TIME.format(start.plusHours(2)));
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("COMPETITION"))
                .andExpect(jsonPath("$.data.joinedCount").value(1))
                .andExpect(jsonPath("$.data.checkinCode").isNotEmpty())
                .andReturn();
        long activityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        String checkinCode = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("checkinCode").asText();
        assertThat(checkinCode).hasSize(6);

        // 创建者视角可见签到码；成员视角不可见（恒为 null）
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkinCode").value(org.hamcrest.Matchers.nullValue()));

        // 2. 成员报名
        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.joined").value(true))
                .andExpect(jsonPath("$.data.checkedIn").value(false));

        // 3. 签到：错码被拒
        mockMvc.perform(post("/api/activities/" + activityId + "/checkin")
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("签到码不正确"));

        // 对码签到成功，重复签到返回幂等成功
        mockMvc.perform(post("/api/activities/" + activityId + "/checkin")
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + checkinCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checkedIn").value(true));
        mockMvc.perform(post("/api/activities/" + activityId + "/checkin")
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + checkinCode + "\"}"))
                .andExpect(status().isOk());

        // 未报名者不能签到
        String strangerName = "str_" + System.nanoTime();
        register(strangerName);
        TokenResponse strangerTokens = login(strangerName, "password1");
        mockMvc.perform(post("/api/activities/" + activityId + "/checkin")
                        .header("Authorization", "Bearer " + strangerTokens.getAccessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + checkinCode + "\"}"))
                .andExpect(status().isForbidden());

        // 4. 成员/签到记录（创建者可见）
        mockMvc.perform(get("/api/activities/" + activityId + "/members").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].checkedIn").value(true))
                .andExpect(jsonPath("$.data[1].checkedInAt").isNotEmpty());

        // 5. 编辑：改标题与人数上限（未开始）
        mockMvc.perform(put("/api/activities/" + activityId)
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"3v3 招新赛（改期）\",\"maxMembers\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("3v3 招新赛（改期）"));

        // 非创建者不能编辑/取消
        mockMvc.perform(put("/api/activities/" + activityId)
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/activities/" + activityId).header("Authorization", memberAuth))
                .andExpect(status().isForbidden());

        // 6. 邀请：创建者邀请陌生人；重复邀请被拒；陌生人接受后进入成员；再响应无效果
        long strangerId = currentUserId("Bearer " + strangerTokens.getAccessToken());
        MvcResult inviteResult = mockMvc.perform(post("/api/activities/" + activityId + "/invites")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + strangerId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andReturn();
        long inviteId = objectMapper.readTree(inviteResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/activities/" + activityId + "/invites")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + strangerId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("已邀请过该用户"));

        // 创建者可查询邀请状态；普通成员不可
        mockMvc.perform(get("/api/activities/" + activityId + "/invites").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].inviteeId").value((int) strangerId))
                .andExpect(jsonPath("$.data[0].status").value(0));
        mockMvc.perform(get("/api/activities/" + activityId + "/invites").header("Authorization", memberAuth))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/activity-invites/pending").header("Authorization", "Bearer " + strangerTokens.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/notifications?unreadOnly=true")
                        .header("Authorization", "Bearer " + strangerTokens.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].type").value("ACTIVITY_INVITED"));

        mockMvc.perform(post("/api/activity-invites/" + inviteId + "/accept")
                        .header("Authorization", "Bearer " + strangerTokens.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1));

        mockMvc.perform(get("/api/activities/" + activityId + "/invites").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value(1));
        mockMvc.perform(get("/api/notifications?unreadOnly=true").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.list[0].type").value("ACTIVITY_MEMBER_JOINED"));

        // 接受后进入成员列表（3 人）
        mockMvc.perform(get("/api/activities/" + activityId + "/members").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3));
        // 已响应的邀请不能再处理
        mockMvc.perform(post("/api/activity-invites/" + inviteId + "/accept")
                        .header("Authorization", "Bearer " + strangerTokens.getAccessToken()))
                .andExpect(status().isBadRequest());

        // 7. 日程：mine 含该活动（created 视角给创建者，joined 视角给成员）
        mockMvc.perform(get("/api/activities/mine?role=created").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(activityId));
        mockMvc.perform(get("/api/activities/mine?role=joined").header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(activityId))
                .andExpect(jsonPath("$.data[0].checkedIn").value(true));

        // 8. 取消活动：创建者可、成员收纪录（列表里 phase=CANCELLED 不再出现在地点活动列表）
        mockMvc.perform(delete("/api/activities/" + activityId).header("Authorization", creatorAuth))
                .andExpect(status().isOk());
        // 已取消活动详情仍可查看（与 /mine 一致），但写操作拒绝
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("CANCELLED"));
        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", memberAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/places/" + placeId + "/activities").header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
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
