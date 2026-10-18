package com.campus;

import com.campus.ai.dto.ChatDetailResponse;
import com.campus.ai.dto.PlanSaveResponse;
import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.mockito.ArgumentCaptor;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 全链路（不开真实大模型）：
 * 一句话 → mock 的工具轮（list_my_maps / submit_plan）→ 最终答复
 * → 会话出现 DRAFT 草稿 → plan/save 建出含 stops 的活动 → 重复保存幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AgentChatMockedAcceptanceTest {

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
    private AiConversationMapper conversationMapper;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Test
    void chatToPlanToActivityLifecycle() throws Exception {
        String username = "agent_" + System.nanoTime();
        register(username);
        TokenResponse tokens = login(username, "password1");
        String auth = "Bearer " + tokens.getAccessToken();

        // mock 三轮：查地图 → 提交草稿 → 最终答复
        LocalDateTime start = LocalDateTime.now().plusHours(1);
        String planJson = """
                {"title":"校园漫步","category":"OTHER","summary":"先图书馆集合再北门散步",
                 "startTime":"%s","endTime":"%s","maxMembers":5,
                 "stops":[{"name":"图书馆集合","longitude":113.3841,"latitude":23.0457,
                  "startTime":"%s","endTime":"%s","transportMode":"WALK","note":"门口集合"}]}
                """.formatted(TIME.format(start), TIME.format(start.plusHours(2)),
                TIME.format(start), TIME.format(start.plusMinutes(30)));
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "list_my_maps", "{}")), 100),
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_2", "submit_plan", planJson)), 300),
                new LlmResponse("我已按你的共享地图偏好生成漫游安排，可保存后邀请朋友报名。",
                        "stop", List.of(), 500));

        // 1. 创建会话
        MvcResult created = mockMvc.perform(post("/api/assistant/chats").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        long chatId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 2. 发消息（SSE 流式）：等待会话完全结束（DONE→complete）
        MvcResult sendResult = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"周六下午5个人漫游校园，最后想找个吃饭的地方\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        sendResult.getAsyncResult(30_000);
        // SSE 事件内容在真机（浏览器 fetch 流）路径上验证；这里用消息表与会话状态断言链路

        // 3. 会话进入 DRAFT：plan_json/版本已落库，assistant 答复已持久化
        JsonNode detail = objectMapper.readTree(
                mockMvc.perform(get("/api/assistant/chats/" + chatId).header("Authorization", auth))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        JsonNode conversation = detail.path("data").path("conversation");
        assertThat(conversation.path("status").asText()).isEqualTo("DRAFT");
        assertThat(conversation.path("planVersion").asInt()).isEqualTo(1);
        assertThat(conversation.path("title").asText()).isNotBlank();
        assertThat(detail.path("data").path("plan").path("title").asText()).isEqualTo("校园漫步");
        assertThat(detail.path("data").path("messages").size()).isEqualTo(2);
        assertThat(detail.path("data").path("plan").path("stops").path(0).path("seq").asInt()).isEqualTo(1);

        // 4. plan/save：草稿落成活动（自动为坐标站点建 place，主场地取首站）
        MvcResult saved = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        PlanSaveResponse saveData = objectMapper.readValue(
                objectMapper.readTree(saved.getResponse().getContentAsString()).path("data").toString(),
                PlanSaveResponse.class);
        long activityId = saveData.getActivityId();
        assertThat(activityId).isPositive();
        assertThat(saveData.getActivity().getStops()).hasSize(1);
        assertThat(saveData.getActivity().getStops().get(0).getName()).isEqualTo("图书馆集合");

        // 5. 幂等：再次保存返回同一个活动
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(activityId));

        // 6. 活动详情可见（内含行程时间线）
        mockMvc.perform(get("/api/activities/" + activityId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("校园漫步"))
                .andExpect(jsonPath("$.data.stops.length()").value(1));

        // 7. 草稿预检兜底：注入非法 placeId 直接拒绝
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/validate")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","startTime":"%s","endTime":"%s",
                                 "stops":[{"name":"n","placeId":99999999}]}
                                """.formatted(TIME.format(start), TIME.format(start.plusHours(1)))))
                .andExpect(status().isNotFound());

        // 8. 会话状态 SAVED
        JsonNode conversationAfter = objectMapper.readTree(
                mockMvc.perform(get("/api/assistant/chats/" + chatId).header("Authorization", auth))
                        .andReturn().getResponse().getContentAsString())
                .path("data").path("conversation");
        assertThat(conversationAfter.path("status").asText()).isEqualTo("SAVED");
        assertThat(conversationAfter.path("savedActivityId").asLong()).isEqualTo(activityId);
    }

    /** 消息携带 userLocation 时，get_my_location 应把本轮上送坐标回给模型（不落库） */
    @Test
    void chatWithUserLocationExposesGetMyLocationTool() throws Exception {
        String username = "agentloc_" + System.nanoTime();
        register(username);
        TokenResponse tokens = login(username, "password1");
        String auth = "Bearer " + tokens.getAccessToken();

        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "get_my_location", "{}")), 80),
                new LlmResponse("已拿到你的实时位置，正在用它规划到修车店的路线。",
                        "stop", List.of(), 120));

        MvcResult created = mockMvc.perform(post("/api/assistant/chats").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        long chatId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult sendStream = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content":"我在哪附近？怎么去修车店",
                                 "userLocation":{"longitude":113.3842,"latitude":23.0459,
                                  "accuracy":18.0,"capturedAt":"2026-10-18T00:57:00"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        sendStream.getAsyncResult(30_000);
        // SSE 线协议：地理事件（前端实时上图通道）已推送
        assertThat(sendStream.getResponse().getContentAsString()).contains("event:geo");

        // 第二轮 LLM 调用的 messages 里应有 role=tool 的定位结果：含上送坐标与 GCJ-02 标注
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient, times(2)).chat(captor.capture(), any(), any(), any());
        List<List<Map<String, Object>>> calls = captor.getAllValues();
        assertThat(calls).hasSize(2);
        String toolJson = calls.get(1).stream()
                .filter(m -> "tool".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content")))
                .findFirst().orElse("");
        assertThat(toolJson).contains("\"available\":true")
                .contains("113.3842").contains("23.0459")
                .contains("18.0").contains("GCJ-02");

        // 助手消息带 geo_json（USER_LOCATION 含上送坐标），刷新后地图可回放
        JsonNode detail = objectMapper.readTree(
                mockMvc.perform(get("/api/assistant/chats/" + chatId).header("Authorization", auth))
                        .andReturn().getResponse().getContentAsString());
        JsonNode messages = detail.path("data").path("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(1).path("geoJson").asText())
                .contains("USER_LOCATION").contains("113.3842").contains("23.0459");
    }

    /** 同参重复调用应被打回（duplicate_call）且不再真实执行——防模型反复搜索烧光轮次预算 */
    @Test
    void duplicateIdenticalToolCallIsRejectedWithoutReexecution() throws Exception {
        String username = "agentdup_" + System.nanoTime();
        register(username);
        TokenResponse tokens = login(username, "password1");
        String auth = "Bearer " + tokens.getAccessToken();

        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "get_my_location", "{}")), 80),
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_2", "get_my_location", "{}")), 80),
                new LlmResponse("好的，位置沿用刚才那次查询结果。", "stop", List.of(), 120));

        MvcResult created = mockMvc.perform(post("/api/assistant/chats").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        long chatId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        MvcResult sendStream = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"我在这附近，直接告诉我怎么安排\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        sendStream.getAsyncResult(30_000);

        // 第 3 次 LLM 调用的消息流里应出现 duplicate_call 打回结果（第二次调用未真实执行）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(llmClient, times(3)).chat(captor.capture(), any(), any(), any());
        List<List<Map<String, Object>>> calls = captor.getAllValues();
        String duplicateTool = calls.get(2).stream()
                .filter(m -> "tool".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content")))
                .filter(c -> c.contains("duplicate_call"))
                .findFirst().orElse("");
        assertThat(duplicateTool).contains("duplicate_call").contains("get_my_location");
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
