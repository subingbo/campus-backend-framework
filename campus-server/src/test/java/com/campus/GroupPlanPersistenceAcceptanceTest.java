package com.campus;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.GroupAgentPlan;
import com.campus.ai.mapper.GroupAgentPlanMapper;
import com.campus.ai.service.GroupPlanPersistenceService;
import com.campus.auth.dto.RegisterRequest;
import com.campus.social.mapper.MessageMapper;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3A：群消息 + 规划快照同事务。快照插入失败时消息必须一起回滚。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GroupPlanPersistenceAcceptanceTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(1700);

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

    @MockitoSpyBean
    private GroupAgentPlanMapper groupAgentPlanMapper;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private GroupPlanPersistenceService persistenceService;
    @Autowired
    private MessageMapper messageMapper;

    @Test
    void persistsMessageAndSnapshotTogether() throws Exception {
        long askerId = createUser("gpp_ok");
        long conversationId = 90001L;
        PlanDto plan = plan();

        GroupPlanPersistenceService.PersistedReply persisted = persistenceService.persist(
                conversationId, askerId, "AI 已生成草稿", plan, 123L, 1);

        assertThat(persisted.messageId()).isNotNull();
        assertThat(persisted.planId()).isNotNull();
        assertThat(messageMapper.selectByConversation(conversationId, null, 10)).hasSize(1);

        GroupAgentPlan snapshot = groupAgentPlanMapper.selectById(persisted.planId());
        assertThat(snapshot.getGroupConversationId()).isEqualTo(conversationId);
        assertThat(snapshot.getMessageId()).isEqualTo(persisted.messageId());
        assertThat(snapshot.getAskerId()).isEqualTo(askerId);
        assertThat(snapshot.getPlanJson()).contains("同事务规划");
    }

    @Test
    void snapshotFailureRollsBackMessage() throws Exception {
        long askerId = createUser("gpp_fail");
        long conversationId = 90002L;
        doThrow(new RuntimeException("injected plan insert failure"))
                .when(groupAgentPlanMapper).insert(any(GroupAgentPlan.class));

        assertThatThrownBy(() -> persistenceService.persist(
                conversationId, askerId, "这条消息不应留下", plan(), 124L, 1))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected plan insert failure");

        assertThat(messageMapper.selectByConversation(conversationId, null, 10)).isEmpty();
    }

    private PlanDto plan() {
        PlanDto plan = new PlanDto();
        plan.setTitle("同事务规划");
        plan.setCategory("OTHER");
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(2));
        plan.setMaxMembers(10);
        PlanStop stop = new PlanStop();
        stop.setName("图书馆");
        stop.setStartTime(start);
        stop.setEndTime(start.plusMinutes(60));
        stop.setTransportMode("WALK");
        plan.setStops(List.of(stop));
        return plan;
    }

    private long createUser(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username + "_" + System.nanoTime());
        request.setPassword("password1");
        request.setNickname("持久化测试");
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.206." + IP_SEQ.getAndIncrement() + ".9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        return userService.getByUsername(request.getUsername()).getId();
    }
}
