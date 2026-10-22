package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.mapper.MessageMapper;
import com.campus.social.service.ChatMessageService;
import com.campus.user.service.UserService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 聊天幂等：同一 clientMsgId 串行/并发重试只落一行，第二次标记 duplicate。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ChatMessageIdempotencyAcceptanceTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1500);

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
    private UserService userService;
    @Autowired
    private ChatMessageService chatMessageService;
    @Autowired
    private MessageMapper messageMapper;

    @Test
    void sameClientMsgIdIsIdempotentSequentiallyAndConcurrently() throws Exception {
        String owner = unique("chat_owner");
        register(owner);
        String ownerAuth = auth(owner);
        long ownerId = userService.getByUsername(owner).getId();

        String member = unique("chat_member");
        register(member);
        long memberId = userService.getByUsername(member).getId();

        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"幂等群\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode groupData = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        long mapId = groupData.path("mapId").asLong();
        String inviteCode = groupData.path("inviteCode").asText();

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", auth(member))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk());

        MvcResult conversation = mockMvc.perform(get("/api/conversations/maps/" + mapId)
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andReturn();
        long conversationId = objectMapper.readTree(conversation.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        // 串行重试：同一 id，第二次 duplicate，数据库仍只有一行
        ChatMessageService.SendResult first = chatMessageService.send(
                ownerId, conversationId, "hello", "cm-sequential");
        ChatMessageService.SendResult second = chatMessageService.send(
                ownerId, conversationId, "hello", "cm-sequential");

        assertThat(first.duplicate()).isFalse();
        assertThat(second.duplicate()).isTrue();
        assertThat(second.response().getId()).isEqualTo(first.response().getId());
        assertThat(second.response().getClientMsgId()).isEqualTo("cm-sequential");
        assertThat(messageMapper.selectByConversation(conversationId, null, 100)).hasSize(1);

        // 并发重试：唯一键兜底，只落一行，恰好一个请求为 duplicate=false
        List<Callable<Boolean>> tasks = List.of(
                () -> chatMessageService.send(ownerId, conversationId, "same", "cm-concurrent").duplicate(),
                () -> chatMessageService.send(ownerId, conversationId, "same", "cm-concurrent").duplicate()
        );
        List<Boolean> duplicates = runParallel(tasks);
        assertThat(duplicates).containsExactlyInAnyOrder(false, true);
        List<ChatMessageResponse> all = messageMapper.selectByConversation(conversationId, null, 100);
        assertThat(all).hasSize(2);
        assertThat(all.stream().filter(m -> "cm-concurrent".equals(m.getClientMsgId()))).hasSize(1);

        // 普通发送（无 clientMsgId）保持向后兼容，不参与去重
        ChatMessageResponse legacy = chatMessageService.send(ownerId, conversationId, "legacy");
        assertThat(legacy.getClientMsgId()).isNull();
        assertThat(messageMapper.selectByConversation(conversationId, null, 100)).hasSize(3);
    }

    private List<Boolean> runParallel(List<Callable<Boolean>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (Callable<Boolean> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
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
        return "10.205." + IP_SEQ.getAndIncrement() + ".9";
    }
}
