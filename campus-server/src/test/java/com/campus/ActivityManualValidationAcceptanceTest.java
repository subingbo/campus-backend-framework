package com.campus;

import com.campus.activity.entity.Activity;
import com.campus.activity.mapper.ActivityMapper;
import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.common.exception.BusinessException;
import com.campus.travel.RouteInfo;
import com.campus.travel.TravelInfoProvider;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 手动活动与 AI 保存共用交通三态：不可行硬阻断、provider 异常降级、不可行优先于未核验；
 * 已开始活动只冻结时间/行程，标题仍可改。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActivityManualValidationAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1100);

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

    @MockitoBean
    private TravelInfoProvider travelInfoProvider;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private ActivityMapper activityMapper;
    @Autowired
    private ActivityMemberMapper activityMemberMapper;

    @Test
    void infeasibleTravelIsRejected() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(route(600));

        String creator = unique("man_conflict");
        register(creator);
        String auth = auth(creator);
        long placeId = createPlace(auth);
        LocalDateTime start = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);

        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityJson(placeId, start, twoStops(start, 5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只留了 5 分钟")));
    }

    @Test
    void providerFailureDegradesForManualCreation() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new BusinessException(500, "route down"));

        String creator = unique("man_degrade");
        register(creator);
        String auth = auth(creator);
        long placeId = createPlace(auth);
        LocalDateTime start = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);

        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityJson(placeId, start, twoStops(start, 30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stops.length()").value(2));
    }

    @Test
    void infeasibleLegWinsOverProviderError() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(route(600))
                .thenThrow(new BusinessException(500, "route down"));

        String creator = unique("man_priority");
        register(creator);
        String auth = auth(creator);
        long placeId = createPlace(auth);
        LocalDateTime start = LocalDateTime.now().plusHours(1).withSecond(0).withNano(0);

        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityJson(placeId, start, threeStops(start))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("只留了 5 分钟")));
    }

    @Test
    void pastStartTimeIsRejected() throws Exception {
        String creator = unique("man_past");
        register(creator);
        String auth = auth(creator);
        long placeId = createPlace(auth);
        LocalDateTime start = LocalDateTime.now().minusMinutes(10).withSecond(0).withNano(0);

        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"title":"过去活动","startTime":"%s","endTime":"%s","maxMembers":8}
                                """.formatted(placeId, TS.format(start), TS.format(start.plusHours(2)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("开始时间不能早于当前时间"));
    }

    @Test
    void startedActivityAllowsTitleButNotScheduleEdits() throws Exception {
        String creator = unique("man_started");
        register(creator);
        String auth = auth(creator);
        long creatorId = userService.getByUsername(creator).getId();
        long placeId = createPlace(auth);

        Activity activity = new Activity();
        activity.setPlaceId(placeId);
        activity.setCreatorId(creatorId);
        activity.setTitle("已开始活动");
        activity.setDescription("");
        activity.setStartTime(LocalDateTime.now().minusMinutes(30));
        activity.setEndTime(LocalDateTime.now().plusHours(1));
        activity.setMaxMembers(10);
        activity.setCategory("OTHER");
        activity.setCheckinCode("123456");
        activity.setCheckinOpenUntil(activity.getStartTime().plusMinutes(15));
        activity.setStatus(1);
        activityMapper.insert(activity);
        activityMemberMapper.insert(activity.getId(), creatorId);

        mockMvc.perform(put("/api/activities/" + activity.getId())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"已开始活动（改名）\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("已开始活动（改名）"));

        mockMvc.perform(put("/api/activities/" + activity.getId())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"" + TS.format(LocalDateTime.now().plusHours(2)) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("活动已开始，不能修改时间或行程"));

        mockMvc.perform(put("/api/activities/" + activity.getId())
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stops\":[{\"name\":\"新站\",\"longitude\":113.41,\"latitude\":23.06}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("活动已开始，不能修改时间或行程"));
    }

    // ---------- 脚手架 ----------

    private RouteInfo route(long seconds) {
        RouteInfo route = new RouteInfo();
        route.setDurationSeconds(seconds);
        route.setDistanceMeters(1000.0);
        route.setEstimated(false);
        route.setProvider("amap");
        return route;
    }

    private String activityJson(long placeId, LocalDateTime start, String stops) {
        return """
                {"placeId":%d,"title":"手动校验活动","startTime":"%s","endTime":"%s","maxMembers":8,
                 "stops":[%s]}
                """.formatted(placeId, TS.format(start), TS.format(start.plusHours(4)), stops);
    }

    private String twoStops(LocalDateTime start, int gapMinutes) {
        LocalDateTime aStart = start.plusMinutes(60);
        LocalDateTime aEnd = aStart.plusMinutes(30);
        LocalDateTime bStart = aEnd.plusMinutes(gapMinutes);
        return """
                {"name":"A 点","longitude":113.3800,"latitude":23.0400,
                 "startTime":"%s","endTime":"%s","transportMode":"WALK"},
                {"name":"B 点","longitude":113.4000,"latitude":23.0600,
                 "startTime":"%s","endTime":"%s","transportMode":"WALK"}
                """.formatted(TS.format(aStart), TS.format(aEnd), TS.format(bStart),
                TS.format(bStart.plusMinutes(60)));
    }

    private String threeStops(LocalDateTime start) {
        LocalDateTime aStart = start.plusMinutes(60);
        LocalDateTime aEnd = aStart.plusMinutes(30);
        LocalDateTime bStart = aEnd.plusMinutes(5);
        LocalDateTime bEnd = bStart.plusMinutes(60);
        LocalDateTime cStart = bEnd.plusMinutes(25);
        return """
                {"name":"A 点","longitude":113.3800,"latitude":23.0400,
                 "startTime":"%s","endTime":"%s","transportMode":"WALK"},
                {"name":"B 点","longitude":113.3900,"latitude":23.0500,
                 "startTime":"%s","endTime":"%s","transportMode":"WALK"},
                {"name":"C 点","longitude":113.4000,"latitude":23.0600,
                 "startTime":"%s","endTime":"%s","transportMode":"WALK"}
                """.formatted(TS.format(aStart), TS.format(aEnd), TS.format(bStart), TS.format(bEnd),
                TS.format(cStart), TS.format(cStart.plusMinutes(60)));
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
                                {"name":"手动校验场地","categoryId":3,
                                 "longitude":113.4090000,"latitude":23.0590000,"confirmNew":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private String forwardedFor() {
        return "10.203." + IP_SEQ.getAndIncrement() + ".9";
    }
}
