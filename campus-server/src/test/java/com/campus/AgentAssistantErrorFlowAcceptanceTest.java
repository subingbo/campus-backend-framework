package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.service.AgentChatListener;
import com.campus.ai.service.AgentService;
import com.campus.travel.RouteInfo;
import com.campus.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent/SSE 异常流验收（计划 AI-02～10；AI-01 已由 AgentChatMockedAcceptanceTest 覆盖）：
 * 非法 JSON / 未知工具 / 路线失败 / POI 空列表 / 估算标注 / 14 轮触顶 / 预取消 / SSE 线协议 / submit_plan 校验失败不落库。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AgentAssistantErrorFlowAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger LOGIN_IP_SEQ = new AtomicInteger(11);
    private static final String ROUTE_ARGS =
            "{\"mode\":\"WALK\",\"fromLng\":113.37,\"fromLat\":23.13,\"toLng\":113.38,\"toLat\":23.14}";

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

    /** LLN 轮次全部 mock；第三方交通信息直接 mock 接口（AI-04/06/08 的失败与降级由桩决定） */
    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private TravelInfoProvider travelInfoProvider;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentService agentService;
    @Autowired
    private com.campus.user.service.UserService userService;
    @Autowired
    private com.campus.ai.mapper.AiConversationMapper conversationMapper;

    private final Map<Long, String> tokens = new HashMap<>();

    @Test
    void ai02_invalidToolJsonFailsSoftThenKeepsReplying() throws Exception {
        long userId = seededUser("ai02_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("route_info", "{不是合法的工具参数{")
                .reply("你说说想要的时间段，我再查路线。"));
        sendMessage(userId, chatId, "查一下路线");

        assertThat(toolContents().get(0)).contains("\"error\"");
        assertThat(detailStatus(userId, chatId)).isEqualTo("CHATTING");
        assertThat(detailMessageCount(userId, chatId)).isEqualTo(2);
    }

    @Test
    void ai03_unknownToolNameFailsSoft() throws Exception {
        long userId = seededUser("ai03_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("dump_every_secret_map", "{}")
                .reply("这个工具我调不出来，换个说法试试。"));
        sendMessage(userId, chatId, "读所有地图");

        assertThat(toolContents().get(0)).contains("未知工具");
        assertThat(detailMessageCount(userId, chatId)).isEqualTo(2);
    }

    @Test
    void ai04_routeProviderFailureReturnsErrorJsonAndFinishes() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new BusinessException(500, "路线查询服务暂时不可用"));
        long userId = seededUser("ai04_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("route_info", ROUTE_ARGS)
                .reply("路线服务好像不太稳定，稍后再试。"));
        sendMessage(userId, chatId, "算一下两站距离");

        assertThat(toolContents().get(0)).contains("路线查询服务暂时不可用");
        assertThat(detailMessageCount(userId, chatId)).isEqualTo(2);
        assertThat(detailStatus(userId, chatId)).isEqualTo("CHATTING");
    }

    @Test
    void ai05_emptyPoiListReturnedAsIs() throws Exception {
        when(travelInfoProvider.searchPoi(anyString(), any(), any(), any())).thenReturn(List.of());
        long userId = seededUser("ai05_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("amap_poi_search", "{\"keyword\":\"火锅城\"}")
                .reply("没搜到这家店，看看别的？"));
        sendMessage(userId, chatId, "找一家火锅城");

        assertThat(toolContents().get(0)).isEqualTo("[]");
    }

    @Test
    void ai06_estimatedRouteFlagsFlowThrough() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute());
        long userId = seededUser("ai06_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("route_info", ROUTE_ARGS)
                .reply("这段路大约 5 分钟（估算）。"));
        sendMessage(userId, chatId, "这两段路多久");

        assertThat(toolContents().get(0))
                .contains("\"estimated\":true")
                .contains("\"provider\":\"estimate\"");
        assertThat(detailMessageCount(userId, chatId)).isEqualTo(2);
    }

    @Test
    void ai07_fourteenRoundBudgetEndsWithManagedError() throws Exception {
        long userId = seededUser("ai07_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> {
            for (int i = 0; i < 14; i++) {
                dsl.tool("route_info", ROUTE_ARGS);
            }
        });
        sendMessage(userId, chatId, "永远跑不完的需求");

        verify(llmClient, times(14)).chat(any(), any(), any(), any());
        assertThat(detailStatus(userId, chatId)).isEqualTo("CHATTING");
        assertThat(detailMessageCount(userId, chatId)).isEqualTo(1);
    }

    @Test
    void ai08_preCancelledListenerRunsNothing() {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute());
        long userId = seededUserSilently("ai08_user");
        com.campus.ai.entity.AiConversation conversation = new com.campus.ai.entity.AiConversation();
        conversation.setUserId(userId);
        conversation.setStatus(com.campus.ai.entity.AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);
        com.campus.ai.entity.AiConversation prepared = conversationMapper.selectById(conversation.getId());

        agentService.run(prepared, "刚提交就取消", new AgentChatListener() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onStatus(String stage) {
            }

            @Override
            public void onDelta(String text) {
            }

            @Override
            public void onPlan(PlanDto plan) {
            }

            @Override
            public void onDone() {
            }

            @Override
            public void onError(String message) {
            }
        });

        // 取消优先于一切：不发 LLM、不产出助手消息，会话保持未动
        verify(llmClient, never()).chat(any(), any(), any(), any());
        com.campus.ai.entity.AiConversation after = conversationMapper.selectById(conversation.getId());
        assertThat(after.getStatus()).isEqualTo(com.campus.ai.entity.AiConversation.STATUS_CHATTING);
        assertThat(after.getPlanVersion()).isZero();
        assertThat(after.getPlanJson()).isNull();
    }

    @Test
    void ai09_sseWireUsesEventLinesAndBlankSeparators() throws Exception {
        long userId = seededUser("ai09_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl.reply("好的，我在。"));
        MvcResult sendResult = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", tokens.get(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "在吗"))))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        sendResult.getAsyncResult(30_000);
        String wire = sendResult.getResponse().getContentAsString();

        assertThat(wire).contains("event:status").contains("event:done").contains("\ndata:");
        assertThat(wire).matches("(?s).*event\\:status\\n.*data\\:.*");
    }

    @Test
    void ai10_invalidSubmitPlanRejectedNoDraftNoSave() throws Exception {
        long userId = seededUser("ai10_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("submit_plan", invalidTimeDraftJson())
                .reply("时间不对，我改改。"));
        sendMessage(userId, chatId, "昨天办活动");

        assertThat(toolContents().get(0)).contains("\"accepted\":false");
        assertThat(detailStatus(userId, chatId)).isEqualTo("CHATTING");
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("暂无可保存的规划草稿"));
    }

    // ---------- 通用脚手架 ----------

    /** mock LLM 轮次序列 stub：链式 .tool(...).reply(...)，按提交顺序消费 */
    private void stubRounds(java.util.function.Consumer<Dsl> setup) {
        Dsl dsl = new Dsl();
        setup.accept(dsl);
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(dsl.rounds.get(0),
                dsl.rounds.subList(1, dsl.rounds.size()).toArray(LlmResponse[]::new));
    }

    private static final class Dsl {
        final List<LlmResponse> rounds = new ArrayList<>();

        Dsl tool(String name, String arguments) {
            rounds.add(new LlmResponse("", "tool_calls",
                    List.of(new LlmToolCall("call_" + (rounds.size() + 1), name, arguments)), 10));
            return this;
        }

        Dsl reply(String text) {
            rounds.add(new LlmResponse(text, "stop", List.of(), 50));
            return this;
        }
    }

    /** 捕获最后一次 LLM 调用消息历史中的工具结果（顺序同产出） */
    @SuppressWarnings("unchecked")
    private List<String> toolContents() {
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chat(captor.capture(), any(), any(), any());
        List<String> contents = new ArrayList<>();
        for (Map<String, Object> message : captor.getValue()) {
            if ("tool".equals(message.get("role"))) {
                contents.add(String.valueOf(message.get("content")));
            }
        }
        return contents;
    }

    private RouteInfo estimatedRoute() {
        RouteInfo estimate = new RouteInfo();
        estimate.setMode("WALK");
        estimate.setDistanceMeters(620.0);
        estimate.setDurationSeconds(300L);
        estimate.setEstimated(true);
        estimate.setProvider("estimate");
        return estimate;
    }

    private String invalidTimeDraftJson() {
        LocalDateTime start = LocalDateTime.now().minusDays(1).withSecond(0).withNano(0);
        return """
                {"title":"AI10 幽灵时段","category":"OTHER","summary":"越时间不算","startTime":"%s","endTime":"%s",
                 "stops":[{"name":"已过去站","longitude":113.371,"latitude":23.133,"startTime":"%s","endTime":"%s"}]}
                """.formatted(start.format(TS), start.plusHours(3).format(TS),
                start.format(TS), start.plusMinutes(60).format(TS));
    }

    /** HTTP 注册+登录（伪造 X-Forwarded-For 隔离 auth:login:{ip} 限流），缓存 token */
    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.119." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.119." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
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

    /** 只注册已存在用户（不取 token），供纯服务层用例使用 */
    private long seededUserSilently(String username) {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        com.campus.user.entity.User user = null;
        try {
            mockMvc.perform(post("/api/auth/register")
                            .header("X-Forwarded-For", "10.119." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(register)))
                    .andExpect(status().isOk());
            user = userService.getByUsername(username);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return user.getId();

    }

    private long createChat(long userId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/chats")
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private void sendMessage(long userId, long chatId, String content) throws Exception {
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", tokens.get(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn()
                .getAsyncResult(30_000);
    }

    private String detailStatus(long userId, long chatId) throws Exception {
        return detailJson(userId, chatId).path("data").path("conversation").path("status").asText();
    }

    private int detailMessageCount(long userId, long chatId) throws Exception {
        return detailJson(userId, chatId).path("data").path("messages").size();
    }

    private JsonNode detailJson(long userId, long chatId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/assistant/chats/" + chatId)
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
