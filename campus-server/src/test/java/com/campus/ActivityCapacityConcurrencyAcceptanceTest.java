package com.campus;

import com.campus.activity.dto.ActivityInviteItem;
import com.campus.activity.dto.ActivityMemberItem;
import com.campus.activity.mapper.ActivityInviteMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报名容量并发验收：直接报名与接受邀请混合抢名额，锁必须覆盖“复核 + 插入 + 邀请状态”。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ActivityCapacityConcurrencyAcceptanceTest {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final AtomicInteger IP_SEQ = new AtomicInteger(500);

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
    private ActivityInviteMapper activityInviteMapper;

    @Test
    void mixedJoinAndInviteAcceptsNeverExceedCapacity() throws Exception {
        String creator = unique("cap_creator");
        register(creator);
        String creatorAuth = auth(creator);
        long creatorId = userId(creator);
        long placeId = createPlace(creatorAuth);
        long activityId = createActivity(creatorAuth, placeId, 5);

        List<Long> joiners = new ArrayList<>();
        List<Long> invitees = new ArrayList<>();
        List<Long> inviteIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String name = unique("cap_join_" + i);
            register(name);
            joiners.add(userId(name));
        }
        for (int i = 0; i < 5; i++) {
            String name = unique("cap_inv_" + i);
            register(name);
            long inviteeId = userId(name);
            invitees.add(inviteeId);
            inviteIds.add(invite(creatorAuth, activityId, inviteeId));
        }

        List<Callable<Object>> tasks = new ArrayList<>();
        for (Long joiner : joiners) {
            tasks.add(() -> tryCall(joiner, () -> {
                activityService.join(activityId);
                return "JOIN_OK";
            }));
        }
        for (int i = 0; i < inviteIds.size(); i++) {
            long inviteId = inviteIds.get(i);
            long invitee = invitees.get(i);
            tasks.add(() -> tryCall(invitee, () -> {
                activityService.respondInvite(inviteId, true);
                return "ACCEPT_OK";
            }));
        }

        List<Object> results = runParallel(tasks);
        long successes = results.stream().filter("JOIN_OK"::equals).count()
                + results.stream().filter("ACCEPT_OK"::equals).count();
        // 创建者占 1 个名额，剩余 4 个名额只能成功 4 次
        assertThat(successes).isEqualTo(4);

        List<ActivityMemberItem> members = asUser(creatorId, () -> activityService.members(activityId));
        assertThat(members).hasSize(5);
        assertThat(members.stream().map(ActivityMemberItem::getUserId).distinct().count()).isEqualTo(5);
        assertThat(members).extracting(ActivityMemberItem::getUserId).contains(creatorId);

        // 邀请状态与成员状态一致：只有真正进入成员列表的邀请才是已接受
        for (int i = 0; i < inviteIds.size(); i++) {
            ActivityInviteItem invite = activityInviteMapper.selectById(inviteIds.get(i));
            Long inviteeId = invitees.get(i);
            boolean member = members.stream()
                    .anyMatch(m -> m.getUserId().equals(inviteeId));
            assertThat(invite.getStatus()).isEqualTo(member ? 1 : 0);
        }
    }

    @Test
    void creatorOccupiesTheOnlySlotWhenCapacityIsOne() throws Exception {
        String creator = unique("cap_solo_creator");
        register(creator);
        String creatorAuth = auth(creator);
        long creatorId = userId(creator);
        long placeId = createPlace(creatorAuth);
        long activityId = createActivity(creatorAuth, placeId, 1);

        String other = unique("cap_solo_other");
        register(other);
        long otherId = userId(other);
        Object outcome = tryCall(otherId, () -> {
            activityService.join(activityId);
            return "JOIN_OK";
        });

        assertThat(outcome).isInstanceOf(String.class).asString().contains("名额已满");
        List<ActivityMemberItem> members = asUser(creatorId, () -> activityService.members(activityId));
        assertThat(members).hasSize(1);
        assertThat(members.get(0).getUserId()).isEqualTo(creatorId);
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
        user.setUsername("cap" + userId);
        user.setNickname("容量用户" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private List<Object> runParallel(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
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

    private long userId(String username) {
        return userService.getByUsername(username).getId();
    }

    private long createPlace(String auth) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"容量测试场地","categoryId":3,
                                 "longitude":113.4060000,"latitude":23.0560000,"confirmNew":true}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();
    }

    private long createActivity(String auth, long placeId, int maxMembers) throws Exception {
        LocalDateTime start = LocalDateTime.now().plusHours(3).withSecond(0).withNano(0);
        MvcResult result = mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeId":%d,"title":"容量并发活动","startTime":"%s","endTime":"%s",
                                 "maxMembers":%d}
                                """.formatted(placeId, TS.format(start), TS.format(start.plusHours(2)), maxMembers)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private long invite(String auth, long activityId, long inviteeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/activities/" + activityId + "/invites")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + inviteeId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private String forwardedFor() {
        return "10.200." + IP_SEQ.getAndIncrement() + ".9";
    }
}
