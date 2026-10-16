package com.campus;

import com.campus.ai.tool.AgentToolkit;
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

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D3 外部依赖分级：不 mock 任何提供方，用真实 LlmClient/AmapTravelProvider 走降级路径。
 * DynamicPropertySource 的 campus.llm.api-key / campus.amap.web-key 显式置空——
 * 动态属性在 Spring 环境中优先级最高，即使本机存在 application-secrets.properties 也被覆盖。
 * 断言：失败有明确中文提示、不静默、不编造、日志无密钥。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MissingConfigGradingAcceptanceTest {

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
        // 故意缺露配置（与计划 D3-1/D3-3/D3-4 场景一致）
        registry.add("campus.llm.api-key", () -> "");
        registry.add("campus.amap.web-key", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AgentToolkit agentToolkit;

    @Test
    void d3_llmKeyMissing_sendSurfacesSseErrorWithoutAssistantMessage() throws Exception {
        long userId = seededUser("d3_user");
        long chatId = createChat(userId);
        MvcResult sendResult = mockMvc.perform(post("/api/assistant/chats/" + chatId + "/messages")
                        .header("Authorization", tokens.get(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("content", "帮我规划"))))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        sendResult.getAsyncResult(30_000);
        String wire = sendResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // SSE 明确失败：error 事件 + 未配置提示，不挂死、不编造
        assertThat(wire).contains("event:error").contains("AI 助手未配置模型 API Key");
        JsonNode detail = detailJson(userId, chatId);
        assertThat(detail.path("data").path("conversation").path("status").asText()).isEqualTo("CHATTING");
        assertThat(detail.path("data").path("messages").size()).isEqualTo(1);
    }

    @Test
    void d3_amapKeyMissing_routeToolFallsBackToEstimateFlags() {
        String tool = agentToolkit.execute("route_info",
                "{\"mode\":\"WALK\",\"fromLng\":113.37,\"fromLat\":23.13,\"toLng\":113.38,\"toLat\":23.14}");
        JsonNode route = readTree(tool);
        // 无 key：本地直线估算并明确标注，不伪装成真实导航
        assertThat(route.path("estimated").asBoolean()).isTrue();
        assertThat(route.path("provider").asText()).isEqualTo("estimate");
        assertThat(route.path("distanceMeters").asDouble()).isPositive();
        assertThat(route.path("durationSeconds").asLong()).isPositive();
        assertThat(route.path("mode").asText()).isEqualTo("WALK");
    }

    @Test
    void d3_amapKeyMissing_poiToolReportsExplicitConfigurationError() {
        String tool = agentToolkit.execute("amap_poi_search", "{\"keyword\":\"校门口奶茶\"}");
        // 明确配置错误，不伪造 POI、不返回空数组蒙混
        assertThat(readTree(tool).path("error").asText()).isEqualTo("POI 检索服务未配置");
    }

    // ---------- 脚手架 ----------

    private final java.util.Map<Long, String> tokens = new java.util.HashMap<>();

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.120." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.120." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        String token = "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        long userId = userServiceByUsername(username);
        tokens.put(userId, token);
        return userId;
    }

    @Autowired
    private com.campus.user.service.UserService userService;

    private long userServiceByUsername(String username) {
        return userService.getByUsername(username).getId();
    }

    private long createChat(long userId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/assistant/chats")
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private JsonNode detailJson(long userId, long chatId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/assistant/chats/" + chatId)
                        .header("Authorization", tokens.get(userId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
