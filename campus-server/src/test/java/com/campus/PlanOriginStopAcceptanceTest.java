package com.campus;

import com.campus.activity.dto.ActivityStopItem;
import com.campus.activity.entity.Activity;
import com.campus.activity.mapper.ActivityMapper;
import com.campus.activity.service.ActivityService;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanSaveResponse;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.plan.PlanSource;
import com.campus.ai.plan.PlanValidator;
import com.campus.ai.service.PlanSaveService;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.common.exception.BusinessException;
import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.mapper.PlaceMapper;
import com.campus.place.service.PlaceService;
import com.campus.security.user.LoginUser;
import com.campus.travel.RouteInfo;
import com.campus.travel.TravelInfoProvider;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 实时起点只留在规划草稿：保存活动时不建 Place、不写 activity_stop，主地点取首个真实场地。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanOriginStopAcceptanceTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1300);

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
    private PlaceService placeService;
    @Autowired
    private PlaceMapper placeMapper;
    @Autowired
    private PlanValidator planValidator;
    @Autowired
    private PlanSaveService planSaveService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private ActivityMapper activityMapper;

    @Test
    void originStopIsNotPersistedAsPlaceOrActivityStop() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(route(60));

        String username = unique("origin_owner");
        register(username);
        long userId = userService.getByUsername(username).getId();
        long venueId = asUser(userId, () -> createVenue("真实场馆"));
        long placesBefore = placeMapper.countAll();

        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        PlanDto plan = new PlanDto();
        plan.setTitle("起点不落库");
        plan.setCategory("OTHER");
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(2));
        plan.setMaxMembers(10);

        PlanStop origin = new PlanStop();
        origin.setName("我的位置");
        origin.setSource(PlanSource.USER_LOCATION);
        origin.setLongitude(113.3800);
        origin.setLatitude(23.0400);
        origin.setStartTime(start);
        origin.setEndTime(start.plusMinutes(10));

        PlanStop venue = new PlanStop();
        venue.setName("真实场馆");
        venue.setPlaceId(venueId);
        venue.setStartTime(start.plusMinutes(30));
        venue.setEndTime(start.plusMinutes(90));
        venue.setTransportMode("WALK");
        plan.setStops(new ArrayList<>(List.of(origin, venue)));

        long conversationId = seedDraft(userId, plan);
        PlanSaveResponse saved = asUser(userId, () -> planSaveService.save(conversationId, 1));

        assertThat(placeMapper.countAll()).isEqualTo(placesBefore);
        Activity activity = activityMapper.selectById(saved.getActivityId());
        assertThat(activity.getPlaceId()).isEqualTo(venueId);

        List<ActivityStopItem> stops = asUser(userId,
                () -> activityService.get(saved.getActivityId()).getStops());
        assertThat(stops).hasSize(1);
        assertThat(stops.get(0).getName()).isEqualTo("真实场馆");
        assertThat(stops.get(0).getPlaceId()).isEqualTo(venueId);
    }

    @Test
    void planWithOnlyOriginIsRejected() throws Exception {
        String username = unique("origin_only");
        register(username);
        long userId = userService.getByUsername(username).getId();

        LocalDateTime start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        PlanDto plan = new PlanDto();
        plan.setTitle("只有起点");
        plan.setCategory("OTHER");
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(1));
        plan.setMaxMembers(10);
        PlanStop origin = new PlanStop();
        origin.setName("我的位置");
        origin.setSource(PlanSource.USER_LOCATION);
        origin.setLongitude(113.3810);
        origin.setLatitude(23.0410);
        origin.setStartTime(start);
        origin.setEndTime(start.plusMinutes(10));
        plan.setStops(new ArrayList<>(List.of(origin)));

        long conversationId = seedDraft(userId, plan);
        assertThatThrownBy(() -> asUser(userId, () -> planSaveService.save(conversationId, 1)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("行程至少需要一个可定位的地点");
    }

    // ---------- 脚手架 ----------

    private long seedDraft(long userId, PlanDto plan) throws Exception {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);

        PlanDto validated = planValidator.validate(plan, userId);
        conversationMapper.updatePlanResult(conversation.getId(),
                objectMapper.writeValueAsString(validated), 1, AiConversation.STATUS_DRAFT);
        return conversation.getId();
    }

    private long createVenue(String name) {
        CreatePlaceRequest request = new CreatePlaceRequest();
        request.setName(name);
        request.setCategoryId(8L);
        request.setLongitude(new BigDecimal("113.3850000"));
        request.setLatitude(new BigDecimal("23.0450000"));
        request.setConfirmNew(true);
        PlaceCreateResponse response = placeService.createPlace(request);
        return response.getPlace().getId();
    }

    private RouteInfo route(long seconds) {
        RouteInfo route = new RouteInfo();
        route.setDurationSeconds(seconds);
        route.setDistanceMeters(800.0);
        route.setEstimated(false);
        route.setProvider("amap");
        return route;
    }

    private <T> T asUser(long userId, Supplier<T> call) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUsername("origin" + userId);
        user.setNickname("起点用户" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
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

    private String forwardedFor() {
        return "10.204." + IP_SEQ.getAndIncrement() + ".9";
    }
}
