package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.plan.PlanValidator;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 F：草稿修订 /plan/revise 验收——版本+1、非法草稿不落库、版本冲突 409、越权 404。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanReviseAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger LOGIN_IP_SEQ = new AtomicInteger(11);

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
    private AiConversationMapper conversationMapper;
    @Autowired
    private com.campus.user.service.UserService userService;
    @Autowired
    private PlanValidator planValidator;

    private final java.util.Map<Long, String> tokens = new java.util.HashMap<>();

    @Test
    void reviseBumpsVersionResetsSavedAndAllowsResave() throws Exception {
        long userId = seededUser("rev01_owner");
        long convId = seedDraft(userId, "rev 一版", 1);
        String auth = tokens.get(userId);

        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(editedPlan("rev 二版"), 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.title").value("rev 二版"))
                .andExpect(jsonPath("$.data.planVersion").value(2))
                .andExpect(jsonPath("$.data.plan.stops[0].seq").value(1));

        AiConversation conv = conversationMapper.selectById(convId);
        assertThat(conv.getPlanVersion()).isEqualTo(2);
        assertThat(conv.getStatus()).isEqualTo(AiConversation.STATUS_DRAFT);

        // 旧版本号修订 → 409
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(editedPlan("过期修订"), 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));

        // 保存 → 修订成 v3 → 再次保存：谱系更新走活动编辑接口，同一个活动被原地更新（评审项：不再重复建档）
        MvcResult saved = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(saved.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();

        MvcResult revised = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(editedPlan("rev 三版"), 2)))
                .andExpect(status().isOk())
                .andReturn();
        // 修订响应携带谱系：前端可见将要更新的活动
        assertThat(objectMapper.readTree(revised.getResponse().getContentAsString())
                .path("data").path("plan").path("lineageActivityId").asLong()).isEqualTo(activityId);
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(activityId));

        // 活动确实被原地更新（标题变化、stops 重建）
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("rev 三版"));
        assertThat(conversationMapper.selectById(convId).getStatus()).isEqualTo(AiConversation.STATUS_SAVED);
        assertThat(conversationMapper.selectById(convId).getSavedActivityId()).isEqualTo(activityId);
    }

    @Test
    void asNewDraftReviseCreatesSeparateActivity() throws Exception {
        long userId = seededUser("rev04_owner");
        long convId = seedDraft(userId, "rev 首版", 1);
        String auth = tokens.get(userId);

        MvcResult saved1 = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        long activity1 = objectMapper.readTree(saved1.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();

        // 显式“复制为新草稿”：asNewDraft=true → 谱系清空；时间挪到另两天（同窗复制属于与
        // 自己已有活动的时间冲突，会被 409 拦截——正是评审要求的精确冲突判定），再次保存创建新活动
        PlanDto copyPlan = editedPlan("rev 分身");
        copyPlan.setStartTime(copyPlan.getStartTime().plusDays(2));
        copyPlan.setEndTime(copyPlan.getEndTime().plusDays(2));
        copyPlan.getStops().get(0).setStartTime(copyPlan.getStops().get(0).getStartTime().plusDays(2));
        copyPlan.getStops().get(0).setEndTime(copyPlan.getStops().get(0).getEndTime().plusDays(2));
        MvcResult revised = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBodyAsNew(copyPlan, 1, true)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode planNode = objectMapper.readTree(revised.getResponse().getContentAsString())
                .path("data").path("plan");
        assertThat(planNode.hasNonNull("lineageActivityId")).isFalse();

        MvcResult save2 = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        long activity2 = objectMapper.readTree(save2.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();
        assertThat(activity2).isNotEqualTo(activity1);

        // 原活动未被谱系更新覆盖
        mockMvc.perform(get("/api/activities/" + activity1).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("rev 首版"));
    }

    @Test
    void reviseInvalidPlanRejectedWithoutVersionBump() throws Exception {
        long userId = seededUser("rev02_owner");
        long convId = seedDraft(userId, "rev 草稿甲", 1);
        String auth = tokens.get(userId);

        PlanDto invalid = editedPlan("时间倒退草稿");
        invalid.setEndTime(invalid.getStartTime());
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(invalid, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        assertThat(conversationMapper.selectById(convId).getPlanVersion()).isEqualTo(1);
        assertThat(conversationMapper.selectById(convId).getPlanJson()).isNotBlank();
    }

    @Test
    void reviseCrossUserDenied() throws Exception {
        long owner = seededUser("rev03_owner");
        long convId = seedDraft(owner, "rev 别人的", 1);
        long intruder = seededUser("rev03_other");
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", tokens.get(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(editedPlan("越权修订"), 1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ---------- 脚手架 ----------

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.122." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.122." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        String token = "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        long userId = userService.getByUsername(username).getId();
        tokens.put(userId, token);
        return userId;
    }

    private long seedDraft(long userId, String title, int version) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);
        conversationMapper.updatePlanResult(conversation.getId(),
                planJson(title), version, AiConversation.STATUS_DRAFT);
        return conversation.getId();
    }

    private PlanDto editedPlan(String title) {
        PlanDto plan = new PlanDto();
        plan.setTitle(title);
        plan.setCategory("OTHER");
        plan.setSummary("阶段 F 修订");
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(3));
        plan.setMaxMembers(20);
        PlanStop stop = new PlanStop();
        stop.setName(title + "Site");
        stop.setLongitude(113.3401);
        stop.setLatitude(23.1011);
        stop.setStartTime(start);
        stop.setEndTime(start.plusMinutes(60));
        stop.setTransportMode("WALK");
        plan.setStops(new ArrayList<>(List.of(stop)));
        return plan;
    }

    private String planJson(String title) {
        try {
            return objectMapper.writeValueAsString(planValidator.validate(editedPlan(title), 0L));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String reviseBody(PlanDto plan, Integer expectedVersion) {
        return reviseBodyCore(plan, expectedVersion, null);
    }

    private String reviseBodyAsNew(PlanDto plan, Integer expectedVersion, boolean asNewDraft) {
        return reviseBodyCore(plan, expectedVersion, asNewDraft);
    }

    private String reviseBodyCore(PlanDto plan, Integer expectedVersion, Boolean asNewDraft) {
        try {
            java.util.Map<String, Object> body = new java.util.HashMap<>();
            body.put("plan", objectMapper.readTree(objectMapper.writeValueAsString(plan)));
            body.put("expectedPlanVersion", expectedVersion);
            if (asNewDraft != null) {
                body.put("asNewDraft", asNewDraft);
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
