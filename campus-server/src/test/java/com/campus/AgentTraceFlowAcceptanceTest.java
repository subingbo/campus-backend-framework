package com.campus;

import com.campus.ai.dto.PlanSaveResponse;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.service.AgentChatListener;
import com.campus.ai.service.AgentService;
import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.mapper.AgentTraceEventMapper;
import com.campus.ai.trace.mapper.AgentTraceSessionMapper;
import com.campus.ai.trace.store.AgentTraceWriter;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.campus.activity.entity.ActivityStop;
import com.campus.activity.mapper.ActivityStopMapper;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent Trace P0 验收：真实 SSE/保存链路 + Testcontainers MySQL/Redis。
 * 不调用真实大模型/高德；工具与路线提供方按真实响应形态 mock。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AgentTraceFlowAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger IP_SEQ = new AtomicInteger(100);

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

    @MockitoBean
    private TravelInfoProvider travelInfoProvider;

    @MockitoSpyBean
    private ActivityStopMapper activityStopMapper;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentTraceWriter traceWriter;
    @Autowired
    private AgentTraceSessionMapper sessionMapper;
    @Autowired
    private AgentTraceEventMapper eventMapper;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private AgentService agentService;
    @Autowired
    private UserService userService;

    @BeforeEach
    void defaultTravel() {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute(300L));
    }

    @Test
    void chatPlanSaveProducesCompleteChainAndValidJsonl() throws Exception {
        String username = "trace_flow_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String planJson = oneStopPlan(start);

        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "list_my_maps", "{}")), 100, 80, 20),
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_2", "route_info",
                                "{\"mode\":\"WALK\",\"fromLng\":113.3800,\"fromLat\":23.0400,"
                                        + "\"toLng\":113.3850,\"toLat\":23.0450}")), 120, 90, 30),
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_3", "submit_plan", planJson)), 200, 150, 50),
                new LlmResponse("已生成草稿，可以保存。", "stop", List.of(), 300, 200, 100));

        send(auth, chatId, "明天上午去图书馆复习两小时");
        traceWriter.flushNow();

        JsonNode detail = json(get("/api/assistant/chats/" + chatId).header("Authorization", auth));
        assertThat(detail.path("data").path("conversation").path("status").asText()).isEqualTo("DRAFT");
        assertThat(detail.path("data").path("conversation").path("planVersion").asInt()).isEqualTo(1);

        long activityId = save(auth, chatId);
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        assertThat(session).isNotNull();
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
        assertThat(session.getDroppedCount()).isZero();
        assertThat(session.getEventCount()).isPositive();
        assertThat(session.getNextSeq()).isEqualTo((long) session.getEventCount());
        assertThat(session.getUserId()).isEqualTo(userId(username));
        assertThat(session.getTotalTokens()).isEqualTo(720L);
        assertThat(session.getPromptTokens()).isEqualTo(520L);
        assertThat(session.getCompletionTokens()).isEqualTo(200L);

        List<AgentTraceEvent> events = events(session.getId());
        assertThat(types(events)).contains(
                "session/start", "stream/open", "turn/start", "step/start",
                "llm/request", "llm/response", "tool/call", "tool/result",
                "map/search", "route/estimate", "plan/validate", "plan/draft",
                "turn/end", "stream/close", "plan/confirm", "activity/created", "session/end");

        // seq 严格递增且无重复
        long expected = 1;
        for (AgentTraceEvent event : events) {
            assertThat(event.getSeq()).isEqualTo(expected++);
        }
        // callId 真实关联
        assertThat(events.stream().filter(e -> "tool/call".equals(e.getEventType()))
                .map(AgentTraceEvent::getCallId)).contains("call_1", "call_2", "call_3");
        assertThat(events.stream().filter(e -> "tool/result".equals(e.getEventType()))
                .map(AgentTraceEvent::getCallId)).contains("call_1", "call_2", "call_3");
        // 活动 ID 与保存结果一致
        AgentTraceEvent created = first(events, "activity/created");
        assertThat(created).isNotNull();
        assertThat(data(created).path("activityId").asLong()).isEqualTo(activityId);
        assertThat(first(events, "session/end").getStatus()).isEqualTo("COMPLETED");
        assertThat(events.stream()
                .filter(e -> "plan/confirm".equals(e.getEventType()))
                .filter(e -> "SUCCESS".equals(e.getStatus()))
                .findFirst()).isPresent();

        // 隐私红线：DB payload 不含草稿标题/地点名/坐标/JWT/API Key
        for (AgentTraceEvent event : events) {
            String payload = event.getPayloadJson();
            assertThat(payload).doesNotContain("校园漫步", "图书馆", "113.38", "23.04",
                    "Bearer", "sk-", "Authorization");
        }

        // API 查询
        JsonNode traceDetail = json(get("/api/agent/traces/" + session.getId()).header("Authorization", auth));
        assertThat(traceDetail.path("data").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(traceDetail.path("data").path("complete").asBoolean()).isTrue();
        mockMvc.perform(get("/api/agent/traces/" + session.getId() + "/events")
                        .header("Authorization", auth).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(events.size()));

        // P1 增强：详情耗时统计（4 轮 LLM；tool/result 按工具名各 1 次）
        JsonNode stats = traceDetail.path("data").path("stats");
        assertThat(stats).isNotEmpty();
        assertThat(statCount(stats, "llm/response", null)).isEqualTo(4);
        assertThat(statCount(stats, "tool/result", "list_my_maps")).isEqualTo(1);
        assertThat(statCount(stats, "tool/result", "route_info")).isEqualTo(1);
        assertThat(statCount(stats, "tool/result", "submit_plan")).isEqualTo(1);
        JsonNode llmStat = statNode(stats, "llm/response", null);
        assertThat(llmStat.path("p50Ms").isNumber()).isTrue();
        assertThat(llmStat.path("p95Ms").asLong())
                .isGreaterThanOrEqualTo(llmStat.path("p50Ms").asLong());

        // P1 增强：草稿版本聚合（v1：1 站，SUBMIT 校验通过）
        JsonNode versions = traceDetail.path("data").path("draftVersions");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).path("version").asInt()).isEqualTo(1);
        assertThat(versions.get(0).path("stopCount").asInt()).isEqualTo(1);
        assertThat(versions.get(0).path("validatePassed").asBoolean()).isTrue();
        assertThat(versions.get(0).path("validatePhase").asText()).isEqualTo("SUBMIT");

        // JSONL 导出：每行合法、首行 meta、无敏感内容
        MvcResult exported = mockMvc.perform(get("/api/agent/traces/" + session.getId() + "/export")
                        .header("Authorization", auth))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(exported)).andExpect(status().isOk());
        assertThat(exported.getResponse().getContentType()).contains("application/x-ndjson");
        String[] lines = exported.getResponse().getContentAsString().split("\n");
        List<JsonNode> parsed = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                parsed.add(objectMapper.readTree(line));
            }
        }
        assertThat(parsed).hasSize(events.size() + 1);
        assertThat(parsed.get(0).path("type").asText()).isEqualTo("trace/meta");
        assertThat(parsed.get(0).path("data").path("complete").asBoolean()).isTrue();
        assertThat(parsed.get(1).path("type").asText()).isEqualTo("session/start");
        assertThat(exported.getResponse().getContentAsString())
                .doesNotContain("校园漫步", "图书馆", "113.38", "Bearer", "sk-");
        // 生成一份真实运行的脱敏样例（target 不入库，随后复制到 docs/agent-trace/sample-session.jsonl）
        Path sample = Path.of("target", "agent-trace", "sample-session.jsonl");
        Files.createDirectories(sample.getParent());
        Files.writeString(sample, exported.getResponse().getContentAsString(), StandardCharsets.UTF_8);
    }

    @Test
    void businessRollbackNeverRecordsActivityCreated() throws Exception {
        String username = "trace_rollback_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "submit_plan", oneStopPlan(start))), 50, 40, 10),
                new LlmResponse("草稿已生成。", "stop", List.of(), 20, 10, 10));
        send(auth, chatId, "排一个计划");
        traceWriter.flushNow();

        doThrow(new RuntimeException("injected stop insert failure"))
                .when(activityStopMapper).insert(any(ActivityStop.class));
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().is5xxServerError());
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        List<AgentTraceEvent> events = events(session.getId());
        assertThat(types(events)).doesNotContain("activity/created");
        assertThat(events.stream()
                .filter(e -> "plan/confirm".equals(e.getEventType()))
                .anyMatch(e -> "FAILED".equals(e.getStatus()))).isTrue();
        assertThat(types(events)).contains("plan/save-error");
        assertThat(session.getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void repeatSaveIsIdempotentAndTraced() throws Exception {
        String username = "trace_idem_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "submit_plan", oneStopPlan(start))), 50, 40, 10),
                new LlmResponse("草稿已生成。", "stop", List.of(), 20, 10, 10));
        send(auth, chatId, "排一个计划");
        traceWriter.flushNow();

        long first = save(auth, chatId);
        traceWriter.flushNow();
        long second = save(auth, chatId);
        traceWriter.flushNow();

        assertThat(second).isEqualTo(first);
        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        List<AgentTraceEvent> events = events(session.getId());
        assertThat(events.stream()
                .filter(e -> "plan/confirm".equals(e.getEventType()))
                .map(e -> {
                    try {
                        return data(e);
                    } catch (Exception ex) {
                        return objectMapper.createObjectNode();
                    }
                })
                .anyMatch(d -> d.path("idempotentReuse").asBoolean(false))).isTrue();
        assertThat(events.stream()
                .filter(e -> "activity/created".equals(e.getEventType()))
                .map(e -> {
                    try {
                        return data(e);
                    } catch (Exception ex) {
                        return objectMapper.createObjectNode();
                    }
                })
                .anyMatch(d -> d.path("idempotentReuse").asBoolean(false))).isTrue();
        assertThat(session.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void otherUserCannotReadTraceAndMissingTokensStayNull() throws Exception {
        String username = "trace_owner_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        when(llmClient.chat(any(), any(), any(), any()))
                .thenReturn(new LlmResponse("你好，我可以帮你规划。", "stop", List.of(), null));
        send(auth, chatId, "你好");
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        assertThat(session).isNotNull();
        assertThat(session.getTotalTokens()).isNull();
        assertThat(session.getPromptTokens()).isNull();
        assertThat(session.getCompletionTokens()).isNull();
        // 会话仍 RUNNING：用户只是问了一句，尚未保存为活动；不伪造 COMPLETED
        assertThat(session.getStatus()).isEqualTo("RUNNING");

        String otherAuth = registerAndLogin("trace_other_" + System.nanoTime());
        mockMvc.perform(get("/api/agent/traces/" + session.getId()).header("Authorization", otherAuth))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/agent/traces/" + session.getId() + "/export")
                        .header("Authorization", otherAuth))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationFailureRecordsRuleIdAndNeverCreatesActivity() throws Exception {
        String username = "trace_invalid_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String invalid = """
                {"category":"OTHER","startTime":"%s","endTime":"%s","stops":[
                  {"name":"图书馆","longitude":113.38,"latitude":23.04,"startTime":"%s","endTime":"%s"}]}
                """.formatted(TS.format(start), TS.format(start.plusHours(2)),
                TS.format(start), TS.format(start.plusHours(1)));
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "submit_plan", invalid)), 50, 40, 10),
                new LlmResponse("草稿被校验拒绝，请补充标题。", "stop", List.of(), 30, 20, 10));
        send(auth, chatId, "帮我排个计划");
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        List<AgentTraceEvent> events = events(session.getId());
        AgentTraceEvent failed = events.stream()
                .filter(e -> "plan/validate".equals(e.getEventType()))
                .filter(e -> "FAILED".equals(e.getStatus()))
                .findFirst().orElse(null);
        assertThat(failed).isNotNull();
        assertThat(failed.getErrorCode()).isEqualTo("TITLE_MISSING");
        assertThat(types(events)).doesNotContain("activity/created");
        assertThat(types(events)).doesNotContain("plan/draft");

        // 无草稿时保存失败，也不能出现 activity/created
        mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isBadRequest());
        traceWriter.flushNow();
        assertThat(types(events(session.getId()))).doesNotContain("activity/created");
    }

    @Test
    void travelConflictRecordsAvailableAndRequiredMinutes() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute(600L));
        String username = "trace_conflict_" + System.nanoTime();
        String auth = registerAndLogin(username);
        long chatId = createChat(auth);
        LocalDateTime start = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String conflict = """
                {"title":"冲突计划","category":"OTHER","startTime":"%s","endTime":"%s","stops":[
                  {"name":"A","longitude":113.3800,"latitude":23.0400,
                   "startTime":"%s","endTime":"%s","transportMode":"WALK"},
                  {"name":"B","longitude":113.3900,"latitude":23.0500,
                   "startTime":"%s","endTime":"%s","transportMode":"WALK"}]}
                """.formatted(TS.format(start), TS.format(start.plusHours(2)),
                TS.format(start), TS.format(start.plusMinutes(30)),
                TS.format(start.plusMinutes(35)), TS.format(start.plusHours(1)));
        when(llmClient.chat(any(), any(), any(), any())).thenReturn(
                new LlmResponse("", "tool_calls",
                        List.of(new LlmToolCall("call_1", "submit_plan", conflict)), 50, 40, 10),
                new LlmResponse("两站之间时间不够，请调整。", "stop", List.of(), 30, 20, 10));
        send(auth, chatId, "两个地方连着去");
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(chatId);
        AgentTraceEvent failed = events(session.getId()).stream()
                .filter(e -> "plan/validate".equals(e.getEventType()))
                .filter(e -> "TRAVEL_TIME_CONFLICT".equals(e.getErrorCode()))
                .findFirst().orElse(null);
        assertThat(failed).isNotNull();
        JsonNode details = data(failed).path("details");
        assertThat(details.path("availableMinutes").asInt()).isEqualTo(5);
        assertThat(details.path("requiredMinutes").asInt()).isEqualTo(10);
        assertThat(types(events(session.getId()))).doesNotContain("activity/created");
    }

    @Test
    void cancelledRunIsTracedAsCancelledWithoutFakeSuccess() throws Exception {
        String username = "trace_cancel_" + System.nanoTime();
        registerAndLogin(username);
        long userId = userId(username);
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);

        AgentChatListener cancelled = new AgentChatListener() {
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
            public void onPlan(com.campus.ai.dto.PlanDto plan) {
            }

            @Override
            public void onDone() {
            }

            @Override
            public void onError(String message) {
            }
        };
        agentService.run(conversation, "随便聊聊", cancelled);
        traceWriter.flushNow();

        AgentTraceSession session = sessionMapper.selectLatestByConversation(conversation.getId());
        List<AgentTraceEvent> events = events(session.getId());
        AgentTraceEvent turnEnd = first(events, "turn/end");
        assertThat(turnEnd).isNotNull();
        assertThat(turnEnd.getStatus()).isEqualTo("CANCELLED");
        JsonNode close = data(first(events, "stream/close"));
        assertThat(close.path("cancelled").asBoolean()).isTrue();
        assertThat(close.path("closeReason").asText()).isEqualTo("client_disconnected");
        // 用户只是取消，不伪造 COMPLETED；交给清理任务标 INTERRUPTED
        assertThat(session.getStatus()).isEqualTo("RUNNING");
        assertThat(types(events)).doesNotContain("activity/created", "session/end");
    }

    // ---------- helpers ----------

    private RouteInfo estimatedRoute(long seconds) {
        RouteInfo route = new RouteInfo();
        route.setMode("WALK");
        route.setDistanceMeters(620.0);
        route.setDurationSeconds(seconds);
        route.setEstimated(true);
        route.setProvider("estimate");
        return route;
    }

    private String oneStopPlan(LocalDateTime start) {
        return """
                {"title":"校园漫步","category":"OTHER","summary":"测试草稿",
                 "startTime":"%s","endTime":"%s","maxMembers":5,
                 "stops":[{"name":"图书馆","longitude":113.3800,"latitude":23.0400,
                  "startTime":"%s","endTime":"%s","transportMode":"WALK","note":"门口集合"}]}
                """.formatted(TS.format(start), TS.format(start.plusHours(2)),
                TS.format(start), TS.format(start.plusHours(1)));
    }

    private void send(String auth, long chatId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", content))))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(30_000);
    }

    private long save(String auth, long chatId) throws Exception {
        MvcResult saved = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        PlanSaveResponse response = objectMapper.readValue(
                objectMapper.readTree(saved.getResponse().getContentAsString()).path("data").toString(),
                PlanSaveResponse.class);
        return response.getActivityId();
    }

    private long createChat(String auth) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/chats").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private JsonNode json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private List<AgentTraceEvent> events(String sessionId) {
        return eventMapper.selectExportBatch(sessionId, 0L, 5000);
    }

    private List<String> types(List<AgentTraceEvent> events) {
        return events.stream().map(AgentTraceEvent::getEventType).toList();
    }

    private AgentTraceEvent first(List<AgentTraceEvent> events, String type) {
        return events.stream().filter(e -> type.equals(e.getEventType())).findFirst().orElse(null);
    }

    private JsonNode statNode(JsonNode stats, String type, String tool) {
        for (JsonNode stat : stats) {
            boolean sameTool = tool == null
                    ? !stat.hasNonNull("tool")
                    : tool.equals(stat.path("tool").asText(null));
            if (type.equals(stat.path("type").asText()) && sameTool) {
                return stat;
            }
        }
        return objectMapper.createObjectNode();
    }

    private int statCount(JsonNode stats, String type, String tool) {
        return statNode(stats, type, tool).path("count").asInt(-1);
    }

    private JsonNode data(AgentTraceEvent event) throws Exception {
        return objectMapper.readTree(event.getPayloadJson());
    }

    private String registerAndLogin(String username) throws Exception {
        String ip = "10.44." + IP_SEQ.getAndIncrement() + ".7";
        RegisterRequest register = new RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname("追踪测试");
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        LoginRequest login = new LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return "Bearer " + data.path("accessToken").asText();
    }

    private long userId(String username) {
        return userService.getByUsername(username).getId();
    }
}
