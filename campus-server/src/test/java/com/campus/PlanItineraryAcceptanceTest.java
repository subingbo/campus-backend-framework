package com.campus;

import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.social.service.ChatMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1.5 链路验收：群聊 @AI → plan_itinerary（确定性求解，地点来自共建地图 & 备注可命中）
 * → submit_plan 原样引用方案时间 → 草稿被后端盖章（groupId/sourceMapIds/来源标签/规划指纹）。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanItineraryAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final AtomicInteger LOGIN_IP_SEQ = new AtomicInteger(81);

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
    private LlmClient llmClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private com.campus.user.service.UserService userService;

    private final Map<String, String> tokens = new java.util.HashMap<>();
    private final Map<String, Long> ids = new java.util.HashMap<>();

    @Test
    void atAiPlansFromGroupMapAndStampsDraft() throws Exception {
        long asker = seededUser("pit_member");
        long owner = seededUser("pit_owner");

        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get("pit_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"pit 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        long mapId = data.path("mapId").asLong();
        long groupId = data.path("groupId").asLong();
        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", tokens.get("pit_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + data.path("inviteCode").asText() + "\"}"))
                .andExpect(status().isOk());

        // 建两个共建地点（备注不同）：讨论命中咖啡；两站联合求解要覆盖 routeConfidence
        long coffee = addPlace("pit_owner", mapId, "pit 栈咖啡", "群收藏咖啡", 1, 113.37610, 23.09610);
        long library = addPlace("pit_owner", mapId, "图书馆", "安静学习", 2, 113.38110, 23.10110);
        mockMvc.perform(post("/api/maps/" + mapId + "/places/" + coffee + "/comments")
                        .header("Authorization", tokens.get("pit_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"适合讨论，带电脑方便\"}"))
                .andExpect(status().isOk());

        MvcResult conv = mockMvc.perform(get("/api/conversations/maps/" + mapId)
                        .header("Authorization", tokens.get("pit_member")))
                .andExpect(status().isOk())
                .andReturn();
        long chatConversationId = objectMapper.readTree(conv.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        LocalDateTime day = LocalDateTime.now().plusDays(1).withHour(14).withMinute(0)
                .withSecond(0).withNano(0);
        stubRounds(dsl -> dsl
                .tool("search_group_map_places", "{\"keyword\":\"讨论\"}")
                .tool("plan_itinerary",
                        "{\"dateFrom\":\"" + WINDOW.format(day) + "\",\"dateTo\":\"" + WINDOW.format(day.plusHours(4))
                                + "\",\"placeIds\":[" + coffee + "," + library
                                + "],\"stayMinutes\":[60,60],\"transportMode\":\"WALK\"}"),
                rounds -> rounds.tool("submit_plan",
                        "{\"title\":\"pit 聚会\",\"category\":\"OTHER\",\"summary\":\"小组咖啡讨论\","
                                + "\"startTime\":\"" + TS.format(day) + "\",\"endTime\":\"" + TS.format(day.plusHours(2))
                                + "\",\"stops\":["
                                + "{\"name\":\"pit 栈咖啡\",\"placeId\":" + coffee + ",\"startTime\":\""
                                + TS.format(day) + "\",\"endTime\":\"" + TS.format(day.plusMinutes(60))
                                + "\",\"transportMode\":\"WALK\",\"stayMinutes\":60,\"note\":\"群里备注：适合讨论\"},"
                                + "{\"name\":\"图书馆\",\"placeId\":" + library + ",\"startTime\":\""
                                + TS.format(day.plusMinutes(90)) + "\",\"endTime\":\"" + TS.format(day.plusMinutes(120))
                                + "\",\"transportMode\":\"WALK\",\"stayMinutes\":30}],"
                                + "\"reason\":\"依据群备注《适合讨论，带电脑方便》\"}")
                        .reply("已按群地图备注选了 pit 栈咖啡与图书馆，14:00-16:00。"));

        chatMessageService.send(ids.get("pit_member"), chatConversationId,
                "@AI 找个适合讨论的地方安排周六下午的聚会");
        String agentContent = waitForAgentMessage(chatConversationId, 12_000);
        assertThat(agentContent).contains("pit 栈咖啡");

        // find tool call to plan_itinerary result：返回方案来自共建地图且含真实占位耗时（无高德 key → estimate）
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chat(captor.capture(), any(), any(), any());
        List<List<Map<String, Object>>> values = captor.getAllValues();
        String itineraryToolJson = values.stream()
                .flatMap(history -> history.stream()
                        .filter(m -> "tool".equals(m.get("role")))
                        .map(m -> String.valueOf(m.get("content"))))
                .filter(content -> content.contains("\"window\"") && content.contains("solutions"))
                .findFirst().orElse("");
        assertThat(itineraryToolJson).contains("pit 栈咖啡");
        assertThat(itineraryToolJson).contains("\"source\":\"GROUP_MAP\"");
        assertThat(itineraryToolJson).contains("\"stayMinutes\":60");
        // 测试环境无高德 key → provider 退化估算，routeConfidence 必须明示 ESTIMATED（不能宣称已验证）
        assertThat(itineraryToolJson).contains("\"routeConfidence\":\"ESTIMATED\"");

        // 草稿被盖章：groupId/sourceMapIds/来源标签/规划指纹 + 路线置信度
        com.campus.ai.entity.AiConversation draftRef = conversationMapper.selectByUser(asker).stream()
                .filter(c -> com.campus.ai.entity.AiConversation.STATUS_DRAFT.equals(c.getStatus()))
                .findFirst().orElse(null);
        assertThat(draftRef).isNotNull();
        com.campus.ai.entity.AiConversation draft = conversationMapper.selectById(draftRef.getId());
        JsonNode plan = objectMapper.readTree(draft.getPlanJson());
        assertThat(plan.path("groupId").asLong()).isEqualTo(groupId);
        assertThat(plan.path("sourceMapIds").get(0).asLong()).isEqualTo(mapId);
        assertThat(plan.path("selectedPlaceIds").get(0).asLong()).isEqualTo(coffee);
        assertThat(plan.path("stops").get(0).path("source").asText()).isEqualTo("GROUP_MAP");
        assertThat(plan.path("planningSourceVersion").asText()).startsWith("fp-");
        // 两站草稿在提交时被校验器重算交通段并覆写置信度（估算环境下仍为 ESTIMATED）
        assertThat(plan.path("routeConfidence").asText()).isEqualTo("ESTIMATED");
        assertThat(plan.path("routeSegments").size()).isEqualTo(1);
        assertThat(plan.path("totalTravelMinutes").asInt()).isGreaterThanOrEqualTo(1);
    }

    // ---------- 脚手架 ----------

    private void stubRounds(java.util.function.Consumer<Dsl> first,
                            java.util.function.Consumer<Dsl> second) {
        Dsl dslA = new Dsl();
        first.accept(dslA);
        Dsl dslB = new Dsl();
        second.accept(dslB);
        List<LlmResponse> merged = new ArrayList<>();
        merged.addAll(dslA.rounds);
        merged.addAll(dslB.rounds);
        AtomicInteger idx = new AtomicInteger();
        when(llmClient.chat(any(), any(), any(), any())).thenAnswer(inv -> {
            LlmResponse response = merged.get(idx.getAndIncrement());
            java.util.function.Consumer<String> sink = inv.getArgument(2);
            if (sink != null && response.content() != null && !response.content().isEmpty()) {
                for (int i = 0; i < response.content().length(); i += 4) {
                    sink.accept(response.content().substring(i, Math.min(response.content().length(), i + 4)));
                }
            }
            return response;
        });
    }

    private static final class Dsl {
        final List<LlmResponse> rounds = new ArrayList<>();

        Dsl tool(String name, String arguments) {
            rounds.add(new LlmResponse("", "tool_calls",
                    List.of(new LlmToolCall("call_" + (rounds.size() + 1), name, arguments)), 40));
            return this;
        }

        Dsl reply(String text) {
            rounds.add(new LlmResponse(text, "stop", List.of(), 80));
            return this;
        }
    }

    private String waitForAgentMessage(long conversationId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            MvcResult list = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                            .header("Authorization", tokens.get("pit_member")))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode messages = objectMapper.readTree(list.getResponse().getContentAsString()).path("data");
            for (JsonNode message : messages) {
                if ("AGENT".equals(message.path("type").asText())) {
                    return message.path("content").asText();
                }
            }
            Thread.sleep(200);
        }
        return "";
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
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.128." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.128." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
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
