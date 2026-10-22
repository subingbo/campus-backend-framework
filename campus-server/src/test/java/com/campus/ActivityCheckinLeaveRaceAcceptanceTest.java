package com.campus;

import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.activity.service.ActivityService;
import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.common.exception.BusinessException;
import com.campus.security.user.LoginUser;
import com.campus.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 签到与取消报名竞态：两者共享 activity_member 行，必须恰好一方成功。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActivityCheckinLeaveRaceAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger IP_SEQ = new AtomicInteger(700);

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
    private ActivityService activityService;
    @Autowired
    private ActivityMemberMapper activityMemberMapper;

    @Test
    void concurrentCheckinAndLeaveHaveExactlyOneWinner() throws Exception {
        String creator = unique("race_creator");
        register(creator);
        String creatorAuth = auth(creator);
        long placeId = createPlace(creatorAuth);

        String member = unique("race_member");
        register(member);
        String memberAuth = auth(member);
        long memberId = userService.getByUsername(member).getId();

        LocalDateTime start = LocalDateTime.now().plusMinutes(10);
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", creatorAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"title":"签到竞态","startTime":"%s","endTime":"%s","maxMembers":8}
                                """.formatted(placeId, TS.format(start), TS.format(start.plusHours(2)))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).path("data");
        long activityId = data.path("id").asLong();
        String code = data.path("checkinCode").asText();

        mockMvc.perform(post("/api/activities/" + activityId + "/join").header("Authorization", memberAuth))
                .andExpect(status().isOk());

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> checkin = pool.submit(() -> {
                startLine.await();
                return tryCall(memberId, () -> {
                    activityService.checkin(activityId, code);
                    return "CHECKIN_OK";
                });
            });
            Future<Object> leave = pool.submit(() -> {
                startLine.await();
                return tryCall(memberId, () -> {
                    activityService.leave(activityId);
                    return "LEAVE_OK";
                });
            });
            startLine.countDown();
            Object checkinResult = checkin.get(30, TimeUnit.SECONDS);
            Object leaveResult = leave.get(30, TimeUnit.SECONDS);

            long successes = ("CHECKIN_OK".equals(checkinResult) ? 1 : 0)
                    + ("LEAVE_OK".equals(leaveResult) ? 1 : 0);
            assertThat(successes).isEqualTo(1);

            Integer checkedIn = activityMemberMapper.selectCheckedIn(activityId, memberId);
            if ("CHECKIN_OK".equals(checkinResult)) {
                assertThat(checkedIn).isEqualTo(1);
            } else {
                assertThat(checkedIn).isNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- 脚手架 ----------

    private Object tryCall(long userId, Supplier<Object> call) {
        try {
            return asUser(userId, call);
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
    }

    private <T> T asUser(long userId, Supplier<T> call) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUsername("race" + userId);
        user.setNickname("竞态用户" + userId);
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

    private long createPlace(String auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"竞态测试场地","categoryId":3,
                                 "longitude":113.4070000,"latitude":23.0570000,"confirmNew":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private String forwardedFor() {
        return "10.201." + IP_SEQ.getAndIncrement() + ".9";
    }
}
