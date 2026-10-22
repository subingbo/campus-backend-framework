package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.common.exception.BusinessException;
import com.campus.notification.dto.NotificationResponse;
import com.campus.notification.dto.NotificationSummary;
import com.campus.notification.service.NotificationService;
import com.campus.security.user.LoginUser;
import com.campus.user.service.UserService;
import com.campus.ws.WsSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 消息中心：通知与业务同事务、dedupe 只推送一次、summary 不重复计数、本人隔离。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class NotificationAcceptanceTest {

    private static final AtomicInteger IP_SEQ = new AtomicInteger(2300);

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
    private WsSessionRegistry wsSessionRegistry;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void notificationIsTransactionalAndDedupePushesOnlyOnce() throws Exception {
        String username = unique("notif_tx");
        register(username);
        long userId = userService.getByUsername(username).getId();

        // 回滚：通知行不留，也不能推送
        TransactionDefinition definition = new DefaultTransactionDefinition();
        TransactionStatus rollbackStatus = transactionManager.getTransaction(definition);
        try {
            notificationService.create(userId, "SYSTEM", "rollback", "不应保留",
                    "NONE", null, null, "rollback-key");
        } finally {
            transactionManager.rollback(rollbackStatus);
        }
        assertThat(totalOf(userId)).isZero();
        verify(wsSessionRegistry, never()).sendToUser(eq(userId), argThat(this::isNotificationFrame));

        // 提交：通知行存在，推送一次
        NotificationService.NotificationCreateResult first = notificationService.create(
                userId, "SYSTEM", "commit", "保留", "NONE", null,
                Map.of("activityId", 1L), "dedupe-key");
        assertThat(first.created()).isTrue();
        assertThat(totalOf(userId)).isEqualTo(1);
        verify(wsSessionRegistry).sendToUser(eq(userId), argThat(this::isNotificationFrame));

        // 重复 dedupe：不新增、不重复推送
        NotificationService.NotificationCreateResult duplicate = notificationService.create(
                userId, "SYSTEM", "commit", "保留", "NONE", null,
                Map.of("activityId", 1L), "dedupe-key");
        assertThat(duplicate.created()).isFalse();
        assertThat(totalOf(userId)).isEqualTo(1);
        verify(wsSessionRegistry).sendToUser(eq(userId), argThat(this::isNotificationFrame));

        // 无 dedupe 的通知允许多条
        notificationService.create(userId, "SYSTEM", "no-key-1", null, "NONE", null, null, null);
        notificationService.create(userId, "SYSTEM", "no-key-2", null, "NONE", null, null, null);
        assertThat(totalOf(userId)).isEqualTo(3);
    }

    @Test
    void summaryDoesNotDoubleCountFriendRequestNotification() throws Exception {
        String senderName = unique("notif_sender");
        register(senderName);
        String senderAuth = auth(senderName);
        long senderId = userService.getByUsername(senderName).getId();

        String receiverName = unique("notif_receiver");
        register(receiverName);
        long receiverId = userService.getByUsername(receiverName).getId();

        mockMvc.perform(post("/api/friends/requests")
                        .header("Authorization", senderAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + receiverId + "}"))
                .andExpect(status().isOk());

        NotificationSummary summary = asUser(receiverId, () -> notificationService.summary(receiverId));
        assertThat(summary.getUnreadNotifications()).isEqualTo(1);
        assertThat(summary.getPendingFriendRequests()).isEqualTo(1);
        assertThat(summary.getUnreadChatConversations()).isZero();
        // 好友申请已经产生一条通知，不能再次计入 total
        assertThat(summary.getTotal()).isEqualTo(1);

        List<NotificationResponse> rows = notificationService.list(receiverId, true, 1, 10).getList();
        assertThat(rows).hasSize(1);
        long notificationId = rows.get(0).getId();

        // 非本人不能标记已读
        assertThatThrownBy(() -> notificationService.markRead(senderId, notificationId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("通知不存在");

        notificationService.markRead(receiverId, notificationId);
        NotificationSummary after = asUser(receiverId, () -> notificationService.summary(receiverId));
        assertThat(after.getUnreadNotifications()).isZero();
        assertThat(after.getPendingFriendRequests()).isEqualTo(1);
        assertThat(after.getTotal()).isZero();
    }

    private long totalOf(long userId) {
        return notificationService.list(userId, false, 1, 100).getTotal();
    }

    private boolean isNotificationFrame(Map<String, Object> frame) {
        return frame != null && "NOTIFICATION".equals(frame.get("type"));
    }

    private <T> T asUser(long userId, Supplier<T> call) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUsername("notif" + userId);
        user.setNickname("通知用户" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
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
        return "10.211." + IP_SEQ.getAndIncrement() + ".9";
    }
}
