package com.campus;

import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.campus.ai.llm.LlmToolCall;
import com.campus.ai.service.GroupAgentService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2：群聊 @AI 时空协同——@AI 触发形态、群体忙闲（GROUP/free busy 授权）工具分支、AGENT 消息卡片回执。
 * 测试直接走 ChatMessageService.send（与 ws CHAT_SEND 同链），LLM 全部 mock。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GroupAgentAcceptanceTest {

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

    @MockitoBean
    private LlmClient llmClient;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.campus.user.service.UserService userService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private GroupAgentService groupAgentService;

    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();
    private final java.util.Map<String, Long> ids = new java.util.HashMap<>();

    @Test
    void groupAtPlanProducesGroupContextToolResultAndAgentMessage() throws Exception {
        long owner = seededUser("grp01_owner");
        long member = seededUser("grp01_member");

        // 互为好友的必要性仅限私聊；群聊@AI 只需成员。建图 + member 加入
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get("grp01_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"grp01 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        long groupId = data.path("groupId").asLong();
        long mapId = data.path("mapId").asLong();
        String invite = data.path("inviteCode").asText();
        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", tokens.get("grp01_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + invite + "\"}"))
                .andExpect(status().isOk());

        // owner 建 agenda 占用 明天 10-11，并授权 GROUP 忙闲给成员看（仅时段）
        LocalDateTime slot = LocalDateTime.now().plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", tokens.get("grp01_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"组会\",\"startTime\":\"" + TS.format(slot.withHour(10))
                                + "\",\"endTime\":\"" + TS.format(slot.withHour(11)) + "\",\"note\":null}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/schedules/share")
                        .header("Authorization", tokens.get("grp01_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"GROUP\",\"targetId\":" + groupId + ",\"grant\":true}"))
                .andExpect(status().isOk());

        // 群会话（地图群聊）
        MvcResult conv = mockMvc.perform(get("/api/conversations/maps/" + mapId)
                        .header("Authorization", tokens.get("grp01_member")))
                .andExpect(status().isOk())
                .andReturn();
        long conversationId = objectMapper.readTree(conv.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        stubRounds(dsl -> dsl
                .tool("find_free_windows",
                        "{\"dateFrom\":\"" + TS.format(slot) + "\",\"dateTo\":\"" + TS.format(slot.withHour(22))
                                + "\",\"durationMinutes\":120}")
                .tool("submit_plan",
                        "{\"title\":\"grp01 组活动\",\"category\":\"OTHER\",\"summary\":\"小组活动\",\"startTime\":\""
                                + TS.format(slot.withHour(11)) + "\",\"endTime\":\"" + TS.format(slot.withHour(13))
                                + "\",\"stops\":[{\"name\":\"grp 场地\",\"longitude\":113.370,\"latitude\":23.100,\"startTime\":\""
                                + TS.format(slot.withHour(11)) + "\",\"endTime\":\"" + TS.format(slot.withHour(13)) + "\"}]}")
                .reply("你们明天 11-13 都有空，已排好。"));

        // @AI 触发（member 发起，owner 的忙闲按授权可见"仅时段"）
        String atMessage = "@AI 明天下午我们什么时候都有空？帮我排一个两小时的小组活动";
        com.campus.social.dto.ChatMessageResponse sent =
                chatMessageService.send(ids.get("grp01_member"), conversationId, atMessage);
        assertThat(sent.getContent()).isEqualTo(atMessage);

        // 等 groupAgent 内线程产出 AGENT 消息（轮询 ≤10s）
        String agentContent = waitForAgentMessage(conversationId, 10_000);
        assertThat(agentContent).contains("11-13").isNotBlank();

        // 校验 find_free_windows 工具行为：群体语境 + 10-11 忙碌被排除、coverage 全 KNOWN
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient, atLeastOnce()).chat(captor.capture(), any(), any(), any());
        List<Map<String, Object>> history = captor.getAllValues().get(captor.getAllValues().size() - 1);
        String toolJson = history.stream()
                .filter(m -> "tool".equals(m.get("role")))
                .map(m -> String.valueOf(m.get("content")))
                .findFirst().orElse("");
        assertThat(toolJson).contains("\"source\":\"group\"");
        String day = slot.toLocalDate().toString();
        // 10-11 的忙碌被排除（起始须无 T10），11 点后的窗口存在
        assertThat(toolJson).doesNotContain("\"start\":\"" + day + "T10");
        assertThat(toolJson).contains("\"start\":\"" + day + "T11");
        assertThat(toolJson).contains("\"status\":\"KNOWN\"");

        // asker 的 AI 会话留下 DRAFT 结果（submit_plan 草稿）
        com.campus.ai.entity.AiConversation after =
                conversationSvc().selectByUser(member).stream()
                        .filter(c -> c.getStatus().equals(com.campus.ai.entity.AiConversation.STATUS_DRAFT))
                        .findFirst().orElse(null);
        assertThat(after).isNotNull();
    }

    @Test
    void atAiOnlyTriggersInGroupWithPrefix() throws Exception {
        long owner = seededUser("grp02_owner");
        long other = seededUser("grp02_other");
        stubRounds(dsl -> dsl.reply("不会触发"));

        // 建群
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get("grp02_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"grp02 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        long mapId = data.path("mapId").asLong();

        // 群聊普通消息（无 @AI）→ 不产生 AGENT
        MvcResult conv = mockMvc.perform(get("/api/conversations/maps/" + mapId)
                        .header("Authorization", tokens.get("grp02_owner")))
                .andExpect(status().isOk())
                .andReturn();
        long convId = objectMapper.readTree(conv.getResponse().getContentAsString()).path("data").path("id").asLong();
        chatMessageService.send(ids.get("grp02_owner"), convId, "帮我们排一个活动");
        Thread.sleep(500);
        MvcResult list = mockMvc.perform(get("/api/conversations/" + convId + "/messages")
                        .header("Authorization", tokens.get("grp02_owner")))
                .andExpect(status().isOk())
                .andReturn();
        String body = list.getResponse().getContentAsString();
        assertThat(body.contains("\"AGENT\"")).isFalse();
        assertThat(ids.get("grp02_other")).isPositive();
    }

    // ---------- 脚手架 ----------

    private com.campus.ai.mapper.AiConversationMapper conversationSvc() {
        return aiConversationMapper;
    }

    @Autowired
    private com.campus.ai.mapper.AiConversationMapper aiConversationMapper;

    private void stubRounds(java.util.function.Consumer<Dsl> setup) {
        Dsl dsl = new Dsl();
        setup.accept(dsl);
        // 模拟真实流式：按调用次序投喂，同时把 content 通过 deltaSink 推给 listener（群聊 finalText 收集路径）
        AtomicInteger idx = new AtomicInteger();
        when(llmClient.chat(any(), any(), any(), any())).thenAnswer(inv -> {
            LlmResponse response = dsl.rounds.get(idx.getAndIncrement());
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
                    List.of(new LlmToolCall("call_" + (rounds.size() + 1), name, arguments)), 30));
            return this;
        }

        Dsl reply(String text) {
            rounds.add(new LlmResponse(text, "stop", List.of(), 60));
            return this;
        }
    }

    private String waitForAgentMessage(long conversationId, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            MvcResult list = mockMvc.perform(get("/api/conversations/" + conversationId + "/messages")
                            .header("Authorization", tokens.get("grp01_member")))
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

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.124." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.124." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
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
