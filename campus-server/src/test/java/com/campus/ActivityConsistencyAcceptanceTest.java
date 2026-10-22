package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.user.service.UserService;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 已取消活动语义：详情/成员可查看（与 mine 一致），写操作仍拒绝，待响应邀请不再展示。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActivityConsistencyAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger IP_SEQ = new AtomicInteger(900);

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
    private UserService userService;

    @Test
    void cancelledActivityDetailMembersAndInvitesStayConsistent() throws Exception {
        String creator = unique("cons_creator");
        register(creator);
        String creatorAuth = auth(creator);
        long placeId = createPlace(creatorAuth);

        String member = unique("cons_member");
        register(member);
        String memberAuth = auth(member);

        String invitee = unique("cons_invitee");
        register(invitee);
        String inviteeAuth = auth(invitee);
        long inviteeId = userService.getByUsername(invitee).getId();

        LocalDateTime start = LocalDateTime.now().plusHours(4);
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"title":"取消一致性活动","startTime":"%s","endTime":"%s","maxMembers":8}
                                """.formatted(placeId, TS.format(start), TS.format(start.plusHours(2)))))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", memberAuth))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/activities/" + activityId + "/invites")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + inviteeId + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/activities/" + activityId).header("Authorization", creatorAuth))
                .andExpect(status().isOk());

        // 详情：成员可见，phase=CANCELLED
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("CANCELLED"));

        // 成员列表：创建者仍可查看
        mockMvc.perform(get("/api/activities/" + activityId + "/members").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // 写操作仍拒绝
        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", inviteeAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/activities/" + activityId + "/checkin")
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isNotFound());

        // 待响应邀请不再出现
        mockMvc.perform(get("/api/activity-invites/pending").header("Authorization", inviteeAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        // 我的日程仍保留管理入口
        mockMvc.perform(get("/api/activities/mine?role=created").header("Authorization", creatorAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(activityId))
                .andExpect(jsonPath("$.data[0].phase").value("CANCELLED"));
    }

    private String unique(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private void register(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", forwardedFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private String auth(String username) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", forwardedFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
    }

    private long createPlace(String auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"一致性测试场地","categoryId":3,
                                 "longitude":113.4080000,"latitude":23.0580000,"confirmNew":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private String forwardedFor() {
        return "10.202." + IP_SEQ.getAndIncrement() + ".9";
    }
}
