package com.campus;

import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.service.GroupMapContextService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1.5/P2 强化：提交前二次校验（开发计划 §七）。
 * 草稿直接以“盖章后”的形态写入 ai_conversation（等价于群聊 @AI 已生成草稿），再驱动 plan/save 与 plan/revise：
 * 1) 退群后保存 → 409；2) 共建地图变化（指纹失效）→ 409；3) KNOWN 成员日程变化 → 409；
 * 4) 无变更修订不重置幂等标记，重复保存不建重复活动。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanSaveGroupRevalidationAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final AtomicInteger LOGIN_IP_SEQ = new AtomicInteger(71);

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
    @Qualifier("redisObjectMapper")
    private ObjectMapper redisObjectMapper;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private com.campus.ai.tool.AgentToolkit toolkit;
    @Autowired
    private GroupMapContextService groupMapContextService;
    @Autowired
    private com.campus.user.service.UserService userService;
    @Autowired
    private com.campus.ai.plan.PlanValidator planValidator;

    private final Map<String, String> tokens = new java.util.HashMap<>();
    private final Map<String, Long> ids = new java.util.HashMap<>();

    @Test
    void groupStampEnablesSaveAndChangesBlockIt() throws Exception {
        long owner = seededUser("h01_owner");
        long member = seededUser("h01_member");
        GroupData g = createGroup("h01", "h01_owner");
        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", tokens.get("h01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + g.invite + "\"}"))
                .andExpect(status().isOk());
        long placeId = addPlace("h01_owner", g.mapId, "h01 集合点", "校内集合", 8, 113.37410, 23.09610);

        LocalDateTime day = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0)
                .withSecond(0).withNano(0);
        String fingerprint = groupMapContextService.fingerprint(List.of(g.mapId));

        // ---- 正常保存：盖章草稿 + 无冲突 ----
        long convId = createChatAndSeedPlan("h01_member",
                stampedPlan(g, placeId, member, day, fingerprint, member, null));
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", tokens.get("h01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").isNumber());

        // ---- 新草稿后共建地图变化 → 409（指纹失效）----
        long convId2 = createChatAndSeedPlan("h01_member",
                stampedPlan(g, placeId, member, day, fingerprint, member, null));
        addPlace("h01_owner", g.mapId, "h01 新地点", "地图变化", 8, 113.38410, 23.10610);
        mockMvc.perform(post("/api/assistant/chats/" + convId2 + "/plan/save")
                        .header("Authorization", tokens.get("h01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isConflict());

        // ---- 新草稿后成员日程变化（授权 KNOWN）→ 409 ----
        long convId3 = createChatAndSeedPlan("h01_member",
                stampedPlan(g, placeId, member, day, fingerprint, member, null));
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", tokens.get("h01_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"突袭组会\",\"startTime\":\"" + TS.format(day.withHour(15))
                                + "\",\"endTime\":\"" + TS.format(day.withHour(16)) + "\",\"note\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/share")
                        .header("Authorization", tokens.get("h01_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"GROUP\",\"targetId\":" + g.groupId + ",\"grant\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/assistant/chats/" + convId3 + "/plan/save")
                        .header("Authorization", tokens.get("h01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isConflict());

        // ---- 退群（owner 移除成员）→ 409 ----
        long convId4 = createChatAndSeedPlan("h01_member",
                stampedPlan(g, placeId, member, day, fingerprint, member, null));
        mockMvc.perform(delete("/api/maps/" + g.mapId + "/members/" + member)
                        .header("Authorization", tokens.get("h01_owner")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/assistant/chats/" + convId4 + "/plan/save")
                        .header("Authorization", tokens.get("h01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void unchangedReviseKeepsSavedActivity() throws Exception {
        long owner = seededUser("h02_owner");
        GroupData g = createGroup("h02", "h02_owner");
        long placeId = addPlace("h02_owner", g.mapId, "h02 地标", "集合点", 8, 113.37410, 23.09612);
        LocalDateTime day = LocalDateTime.now().plusDays(2).withHour(10).withMinute(0)
                .withSecond(0).withNano(0);
        String fingerprint = groupMapContextService.fingerprint(List.of(g.mapId));

        long convId = createChatAndSeedPlan("h02_owner",
                stampedPlan(g, placeId, owner, day, fingerprint, owner, null));
        MvcResult saved = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", tokens.get("h02_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(saved.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();

        // 无变更修订（同内容 + 旧版本号）→ 200 且不升版、不重置幂等标记
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/revise")
                        .header("Authorization", tokens.get("h02_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reviseBody(stampedPlan(g, placeId, owner, day, fingerprint, owner, null), 1)))
                .andExpect(status().isOk());
        AiConversation after = conversationMapper.selectById(convId);
        assertThat(after.getStatus()).isEqualTo(AiConversation.STATUS_SAVED);
        assertThat(after.getSavedActivityId()).isEqualTo(activityId);
        assertThat(after.getPlanVersion()).isEqualTo(1);

        // 再次保存 → 幂等返回原活动，不重复建活动
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", tokens.get("h02_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(activityId));
    }

    // ---------- 脚手架 ----------

    /** 评审算例：用户“14:00-16:00 篮球”与“新规划 15:00-17:00 讨论”重叠 → 保存 409；
     * 同一 drafting 带谱系（lineage=该活动）→ 走活动更新接口原地更新，不再重复建档。 */
    @Test
    void ownOtherActivityBlocksAndLineageUpdates() throws Exception {
        long owner = seededUser("h03_owner");
        GroupData g = createGroup("h03", "h03_owner");
        long placeId = addPlace("h03_owner", g.mapId, "h03 篮球场", "集合点", 8, 113.37412, 23.09614);
        LocalDateTime day = LocalDateTime.now().plusDays(3).withHour(15).withMinute(0)
                .withSecond(0).withNano(0);
        String fingerprint = groupMapContextService.fingerprint(List.of(g.mapId));

        // 用户自己的另一个活动：14:00-16:00
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", tokens.get("h03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":\"" + placeId + "\",\"title\":\"篮球赛\",\"description\":null,"
                                + "\"category\":\"OTHER\",\"startTime\":\"" + TS.format(day.withHour(14))
                                + "\",\"endTime\":\"" + TS.format(day.withHour(16)) + "\",\"maxMembers\":20}"))
                .andExpect(status().isOk())
                .andReturn();
        long basketball = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 新草稿 15:00-17:00（与自己的活动重叠，无谱系）→ 409
        long convA = createChatAndSeedPlan("h03_owner",
                stampedPlan(g, placeId, owner, day, fingerprint, owner, null));
        mockMvc.perform(post("/api/assistant/chats/" + convA + "/plan/save")
                        .header("Authorization", tokens.get("h03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isConflict());

        // 谱系 = 篮球活动本身 → 唯一豁免 → 原地更新，activityId 不变
        long convB = createChatAndSeedPlan("h03_owner",
                stampedPlan(g, placeId, owner, day, fingerprint, owner, basketball));
        mockMvc.perform(post("/api/assistant/chats/" + convB + "/plan/save")
                        .header("Authorization", tokens.get("h03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(basketball));
        mockMvc.perform(get("/api/activities/" + basketball).header("Authorization", tokens.get("h03_owner")))
                .andExpect(jsonPath("$.data.title").value("h 群活动"));
    }

    /**
     * 盖章形态草稿：与群聊 @AI 流程产出同构——构造 PlanDto 后经真实校验器重整，
     * 再用 redisObjectMapper 序列化（revise 等值比对才能命中无变更守卫）。
     */
    private String stampedPlan(GroupData g, long placeId, Long coverageUserId,
                               LocalDateTime day, String fingerprint, Long userId,
                               Long lineageActivityId) throws Exception {
        com.campus.ai.dto.PlanStop stop = new com.campus.ai.dto.PlanStop();
        stop.setName("h 集合点");
        stop.setPlaceId(placeId);
        stop.setStartTime(day);
        stop.setEndTime(day.plusHours(2));
        stop.setTransportMode("WALK");
        stop.setStayMinutes(60);
        stop.setSource("GROUP_MAP");
        stop.setMapId(g.mapId);

        com.campus.ai.dto.PlanDto plan = new com.campus.ai.dto.PlanDto();
        plan.setTitle("h 群活动");
        plan.setCategory("OTHER");
        plan.setSummary("共建地图驱动的小聚");
        plan.setStartTime(day);
        plan.setEndTime(day.plusHours(2));
        plan.setMaxMembers(20);
        plan.setStops(List.of(stop));
        plan.setGroupId(g.groupId);
        plan.setSourceMapIds(List.of(g.mapId));
        plan.setSelectedPlaceIds(List.of(placeId));
        plan.setPlanningSourceVersion(fingerprint);
        plan.setConstraintStatus("FEASIBLE");
        com.campus.ai.dto.PlanDto.PlanCoverage coverage = new com.campus.ai.dto.PlanDto.PlanCoverage();
        coverage.setUserId(coverageUserId);
        coverage.setStatus("KNOWN");
        plan.setAvailabilityCoverage(List.of(coverage));
        plan.setTotalTravelMinutes(0);
        plan.setLineageActivityId(lineageActivityId);

        com.campus.ai.dto.PlanDto validated = planValidator.validate(plan, userId);
        return redisObjectMapper.writeValueAsString(validated);
    }
    private String reviseBody(String planJson, int expectedVersion) throws Exception {
        JsonNode node = redisObjectMapper.readTree(planJson);
        return objectMapper.writeValueAsString(Map.of("plan", node, "expectedPlanVersion", expectedVersion));
    }

    private long createChatAndSeedPlan(String username, String planJson) throws Exception {
        MvcResult chat = mockMvc.perform(post("/api/assistant/chats")
                        .header("Authorization", tokens.get(username)))
                .andExpect(status().isOk())
                .andReturn();
        long convId = objectMapper.readTree(chat.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        conversationMapper.updatePlanResult(convId, planJson, 1, AiConversation.STATUS_DRAFT);
        return convId;
    }

    private record GroupData(long groupId, long mapId, String invite) {
    }

    private GroupData createGroup(String tag, String ownerTokenKey) throws Exception {
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get(ownerTokenKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tag + " 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        return new GroupData(data.path("groupId").asLong(), data.path("mapId").asLong(),
                data.path("inviteCode").asText());
    }

    private long addPlace(String callerTokenKey, long mapId, String name, String description,
                          long categoryId, double lng, double lat) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", tokens.get(callerTokenKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + description
                                + "\",\"categoryId\":" + categoryId
                                + ",\"longitude\":" + lng + ",\"latitude\":" + lat
                                + ",\"confirmNew\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("added").asBoolean()).isTrue();
        return data.path("place").path("id").asLong();
    }

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.127." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.127." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        String token = "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        tokens.put(username, token);
        long userId = userService.getByUsername(username).getId();
        ids.put(username, userId);
        return userId;
    }
}
