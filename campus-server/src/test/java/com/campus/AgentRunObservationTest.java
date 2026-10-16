package com.campus;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 阶段 E1 观测验收：AI_RUN 单行 JSON 的字段与数值可校验；E2 同参去重后真实工具只执行一次。
 * 本类以 campus.ai.efficiency.enabled=true 启动（E2 生产默认 false，优化对照专用）。
 */
@SpringBootTest(properties = {"spring.profiles.active=test", "campus.ai.efficiency.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AgentRunObservationTest {

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

    @MockitoBean
    private LlmClient llmClient;

    @MockitoBean
    private TravelInfoProvider travelInfoProvider;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.campus.user.service.UserService userService;
    @Autowired
    private com.campus.ai.mapper.AiConversationMapper conversationMapper;

    private final Map<Long, String> tokens = new HashMap<>();
    private static final Logger RUN_LOGGER = (Logger) LoggerFactory.getLogger("AI_RUN");
    private static final ListAppender<ILoggingEvent> RUN_CAPTURE = new ListAppender<>();

    @BeforeEach
    void prepareCapture() {
        // Spring 首次 bootstrap 时会重置 logback 配置清掉运行期挂的 appender；
        // 每个用例执行前重新挂（此时 context 已就绪，不会再被清）
        RUN_LOGGER.setLevel(Level.INFO);
        RUN_CAPTURE.start();
        if (RUN_LOGGER.getAppender("AI_RUN_CAPTURE") == null) {
            RUN_CAPTURE.setName("AI_RUN_CAPTURE");
            RUN_LOGGER.addAppender(RUN_CAPTURE);
        }
        RUN_CAPTURE.list.clear();
    }

    @AfterAll
    static void detachCapture() {
        RUN_LOGGER.detachAndStopAllAppenders();
    }

    @Test
    void multiRoundRunEmitsOneAlignedObservationLine() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute());
        long userId = seededUser("obs01_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("list_my_maps", "{}")
                .tool("route_info", ROUTE_ARGS)
                .tool("submit_plan", validDraftJson())
                .reply("草稿已生成，可以保存。"));
        sendMessage(userId, chatId, "排一个三小时的草稿");
        sendResultExpectation(chatId, userId, "DRAFT");

        // AI_RUN 行在 SSE 派发完成后由 finally 写出，存在毫秒级时滞——等待出现而非硬断言
        JsonNode observation = awaitObservationLine();
        assertThat(observation.path("conversationId").asLong()).isEqualTo(chatId);
        assertThat(observation.path("userId").asLong()).isEqualTo(userId);
        assertThat(observation.path("rounds").asInt()).isEqualTo(4);
        assertThat(observation.path("tokensTotal").asInt()).isEqualTo(10 + 20 + 30 + 50);
        List<JsonNode> tools = new ArrayList<>();
        observation.path("tools").forEach(tools::add);
        assertThat(tools).hasSize(3);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("list_my_maps");
        assertThat(tools.get(1).path("name").asText()).isEqualTo("route_info");
        assertThat(tools.get(2).path("name").asText()).isEqualTo("submit_plan");
        assertThat(tools).allSatisfy(t -> assertThat(t.path("ok").asBoolean()).isTrue());
        assertThat(observation.path("routeCalls").asInt()).isEqualTo(1);
        assertThat(observation.path("routeEstimated").asInt()).isEqualTo(1);
        assertThat(observation.path("repeatHits").asInt()).isEqualTo(0);
        assertThat(observation.path("firstVisibleMs").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(observation.path("totalMs").asLong()).isGreaterThan(0);
        assertThat(observation.path("hasDraft").asBoolean()).isTrue();
        assertThat(observation.path("outcome").asText()).isEqualTo("DRAFT");
        assertThat(observation.path("stopCause").asText()).isEqualTo("draft");
        // 效率附录生效：可截获 system 消息中的效率提示
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, times(4)).chat(captor.capture(), any(), any(), any());
        @SuppressWarnings("unchecked")
        String systemText = String.valueOf(captor.getAllValues().get(0).get(0).get("content"));
        assertThat(systemText).contains("效率提示").contains("list_map_places");
        // 正文红线：观测行不含用户输入与草稿标题
        String line = RUN_CAPTURE.list.get(0).getFormattedMessage();
        assertThat(line).doesNotContain("排一个三小时的草稿").doesNotContain("观测草稿");
    }

    @Test
    void repeatedSameArgsToolExecutesOnceAndCountsRepeatHit() throws Exception {
        when(travelInfoProvider.searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(estimatedRoute());
        long userId = seededUser("obs02_user");
        long chatId = createChat(userId);
        stubRounds(dsl -> dsl
                .tool("route_info", ROUTE_ARGS)
                .tool("route_info", ROUTE_ARGS)
                .reply("都查好了。"));
        sendMessage(userId, chatId, "同一段路查两遍");

        JsonNode observation = awaitObservationLine();
        assertThat(observation.path("repeatHits").asInt()).isEqualTo(1);
        List<JsonNode> tools = new ArrayList<>();
        observation.path("tools").forEach(tools::add);
        assertThat(tools).hasSize(2);
        assertThat(tools).allSatisfy(t -> {
            assertThat(t.path("name").asText()).isEqualTo("route_info");
            assertThat(t.path("ok").asBoolean()).isTrue();
        });
        // 真实工具只执行一次，第二次命中 run 内缓存
        verify(travelInfoProvider, times(1))
                .searchRoute(anyString(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertThat(observation.path("outcome").asText()).isEqualTo("ANSWERED");
    }

    // ---------- 脚手架 ----------

    /** 等 AI_RUN 观测行出现（emit 在 SSE 派发完成后的 finally 中，有毫秒级时滞） */
    private JsonNode awaitObservationLine() throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && RUN_CAPTURE.list.isEmpty()) {
            Thread.sleep(20);
        }
        assertThat(RUN_CAPTURE.list).hasSize(1);
        return objectMapper.readTree(RUN_CAPTURE.list.get(0).getFormattedMessage());
    }

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
                    List.of(new LlmToolCall("call_" + (rounds.size() + 1), name, arguments)), 10 * (rounds.size() + 1)));
            return this;
        }

        Dsl reply(String text) {
            rounds.add(new LlmResponse(text, "stop", List.of(), 50));
            return this;
        }
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

    private String validDraftJson() {
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        return """
                {"title":"观测草稿","category":"OTHER","summary":"e1","startTime":"%s","endTime":"%s",
                 "stops":[{"name":"起点站","longitude":113.371,"latitude":23.133,"startTime":"%s","endTime":"%s"}]}
                """.formatted(start.format(TS), start.plusHours(3).format(TS),
                start.format(TS), start.plusMinutes(90).format(TS));
    }

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.121." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.121." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
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

    private void sendResultExpectation(long chatId, long userId, String status) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/assistant/chats/" + chatId)
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("conversation").path("status").asText()).isEqualTo(status);
    }
}
