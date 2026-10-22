package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.entity.GroupAgentPlan;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.mapper.GroupAgentPlanMapper;
import com.campus.ai.plan.PlanSource;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.user.service.UserService;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3B：群规划摘要白名单、成员权限、移出群后失效、发起人 handoff。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GroupPlanSharingAcceptanceTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1900);

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
    private UserService userService;
    @Autowired
    private AiConversationMapper aiConversationMapper;
    @Autowired
    private GroupAgentPlanMapper groupAgentPlanMapper;
    @Autowired
    private MapMemberMapper mapMemberMapper;

    @Test
    void groupPlanSummaryRespectsWhitelistAndMembership() throws Exception {
        String ownerName = unique("gps_owner");
        register(ownerName);
        String ownerAuth = auth(ownerName);
        long ownerId = userService.getByUsername(ownerName).getId();

        String memberName = unique("gps_member");
        register(memberName);
        String memberAuth = auth(memberName);
        long memberId = userService.getByUsername(memberName).getId();

        String outsiderName = unique("gps_outsider");
        register(outsiderName);
        String outsiderAuth = auth(outsiderName);

        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"摘要测试群\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long mapId = objectMapper.readTree(group.getResponse().getContentAsString())
                .path("data").path("mapId").asLong();
        String inviteCode = objectMapper.readTree(group.getResponse().getContentAsString())
                .path("data").path("inviteCode").asText();

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", memberAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk());

        MvcResult convResult = mockMvc.perform(get("/api/conversations/maps/" + mapId)
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andReturn();
        long conversationId = objectMapper.readTree(convResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        AiConversation ai = new AiConversation();
        ai.setUserId(ownerId);
        ai.setStatus(AiConversation.STATUS_CHATTING);
        ai.setPlanVersion(1);
        ai.setSourceGroupConversationId(conversationId);
        ai.setSourceMapId(mapId);
        aiConversationMapper.insert(ai);
        aiConversationMapper.markSaved(ai.getId(), 999L);

        GroupAgentPlan snapshot = new GroupAgentPlan();
        snapshot.setGroupConversationId(conversationId);
        snapshot.setMessageId(777L);
        snapshot.setAiConversationId(ai.getId());
        snapshot.setAskerId(ownerId);
        snapshot.setPlanVersion(1);
        snapshot.setPlanJson(redisObjectMapper.writeValueAsString(plan(mapId)));
        groupAgentPlanMapper.insert(snapshot);
        long planId = snapshot.getId();

        // 成员可看摘要列表/单卡
        MvcResult listResult = mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans")
                        .header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].planId").value(planId))
                .andExpect(jsonPath("$.data[0].title").value("公开摘要测试"))
                .andExpect(jsonPath("$.data[0].savedActivityId").value(999))
                .andExpect(jsonPath("$.data[0].stops.length()").value(2))
                .andExpect(jsonPath("$.data[0].stops[0].locationVisible").value(true))
                .andExpect(jsonPath("$.data[0].stops[1].locationVisible").value(false))
                .andExpect(jsonPath("$.data[0].stops[1].placeId").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        String listJson = listResult.getResponse().getContentAsString();
        assertThat(listJson).doesNotContain("planningSourceVersion", "selectedPlaceIds",
                "私密备注A", "私密备注B", "secret-fingerprint", "\"note\"", "\"source\"");
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans/" + planId)
                        .header("Authorization", memberAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").value(planId));

        // 非成员不可见
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans")
                        .header("Authorization", outsiderAuth))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans/" + planId)
                        .header("Authorization", outsiderAuth))
                .andExpect(status().isForbidden());

        // handoff 仅发起人
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans/" + planId + "/handoff")
                        .header("Authorization", memberAuth))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans/" + planId + "/handoff")
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiConversationId").value(ai.getId()))
                .andExpect(jsonPath("$.data.sourceMapId").value(mapId))
                .andExpect(jsonPath("$.data.sourceGroupConversationId").value(conversationId));

        // 被移出群后，即使知道 conversationId/planId 也失效
        mapMemberMapper.deleteByMapAndUser(mapId, memberId);
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans")
                        .header("Authorization", memberAuth))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans/" + planId)
                        .header("Authorization", memberAuth))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/conversations/" + conversationId + "/agent-plans")
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk());
    }

    private PlanDto plan(long mapId) {
        PlanDto plan = new PlanDto();
        plan.setTitle("公开摘要测试");
        plan.setCategory("OTHER");
        plan.setSummary("群成员可见摘要");
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(2));
        plan.setMaxMembers(10);
        plan.setSourceMapIds(List.of(mapId));
        plan.setSelectedPlaceIds(List.of(100L, 200L));
        plan.setPlanningSourceVersion("secret-fingerprint");
        plan.setConstraintStatus("FEASIBLE");
        plan.setRouteConfidence("AMAP");
        plan.setTotalTravelMinutes(8);
        plan.setReason("建议步行");
        plan.setWarnings(List.of("提前十分钟到"));

        PlanStop shared = new PlanStop();
        shared.setSeq(1);
        shared.setName("共建地点");
        shared.setPlaceId(100L);
        shared.setLongitude(113.38);
        shared.setLatitude(23.04);
        shared.setStartTime(start);
        shared.setEndTime(start.plusMinutes(30));
        shared.setTransportMode("WALK");
        shared.setSource(PlanSource.GROUP_MAP);
        shared.setNote("私密备注A");

        PlanStop privateStop = new PlanStop();
        privateStop.setSeq(2);
        privateStop.setName("私人地点");
        privateStop.setPlaceId(200L);
        privateStop.setLongitude(113.39);
        privateStop.setLatitude(23.05);
        privateStop.setStartTime(start.plusMinutes(40));
        privateStop.setEndTime(start.plusMinutes(70));
        privateStop.setTransportMode("WALK");
        privateStop.setSource(PlanSource.PERSONAL_MAP);
        privateStop.setNote("私密备注B");

        PlanStop origin = new PlanStop();
        origin.setSeq(3);
        origin.setName("我的位置");
        origin.setLongitude(113.40);
        origin.setLatitude(23.06);
        origin.setSource(PlanSource.USER_LOCATION);

        plan.setStops(List.of(shared, privateStop, origin));

        PlanDto.PlanMember member = new PlanDto.PlanMember();
        member.setUserId(1L);
        member.setNickname("成员");
        member.setRole("EDITOR");
        member.setStatus("KNOWN");
        plan.setMembers(List.of(member));

        PlanDto.PlanConflict conflict = new PlanDto.PlanConflict();
        conflict.setUserId(1L);
        conflict.setNickname("成员");
        conflict.setStartTime(start);
        conflict.setEndTime(start.plusMinutes(30));
        conflict.setSeverity("BLOCKING");
        conflict.setOverlapMinutes(30);
        conflict.setSuggestion("换时间");
        plan.setConflicts(List.of(conflict));

        PlanDto.PlanRouteSegment segment = new PlanDto.PlanRouteSegment();
        segment.setFromSeq(1);
        segment.setToSeq(2);
        segment.setTransportMode("WALK");
        segment.setDistanceMeters(500.0);
        segment.setDurationMinutes(8);
        plan.setRouteSegments(List.of(segment));
        return plan;
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

    private String forwardedFor() {
        return "10.207." + IP_SEQ.getAndIncrement() + ".9";
    }
}
