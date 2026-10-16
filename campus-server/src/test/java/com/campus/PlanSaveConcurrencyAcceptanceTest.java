package com.campus;

import com.campus.activity.dto.ActivityStopItem;
import com.campus.activity.mapper.ActivityStopMapper;
import com.campus.activity.service.ActivityService;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.plan.PlanValidator;
import com.campus.ai.service.PlanSaveService;
import com.campus.common.exception.BusinessException;
import com.campus.ai.dto.PlanSaveResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.mapper.PlaceMapper;
import com.campus.place.service.PlaceService;
import com.campus.security.user.LoginUser;
import com.campus.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
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

import java.math.BigDecimal;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB-01～08：并发幂等、事务回滚、5m 地点融合与多站编辑一致性（计划阶段 C）。
 * HTTP 用例走 MockMvc（串行）；并发用例走服务层多线程（MockMvc 非线程安全），
 * 每线程注入独立 SecurityContext（SecurityUtils.getUserId 全链路可用）。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PlanSaveConcurrencyAcceptanceTest {

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
        // 并发用例每线程一个事务连接同时持有，池容必须 ≥ 并发数
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "12");
    }

    @MockitoBean
    private com.campus.ai.llm.LlmClient llmClient;

    /** Spy 而非 Mock：未注入异常的用例仍走真实 Mapper（stops 真实落库/查询） */
    @MockitoSpyBean
    private ActivityStopMapper activityStopMapper;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserService userService;
    @Autowired
    private AiConversationMapper conversationMapper;
    @Autowired
    private PlanValidator planValidator;
    @Autowired
    private PlanSaveService planSaveService;
    @Autowired
    private PlaceService placeService;
    @Autowired
    private ActivityService activityService;
    @Autowired
    private PlaceMapper placeMapper;

    @Test
    void db01_sequentialDoubleSaveReturnsSameActivity() throws Exception {
        String username = "db01_owner";
        long userId = createHttpUser(username);
        long convId = seedDraft(userId, "db01 草稿", 1, null);
        String auth = loginAuth(username);

        MvcResult first = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(first.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();

        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(activityId));

        mockMvc.perform(get("/api/activities/mine").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        assertThat(asUser(userId, () -> activityService.get(activityId).getStops())).hasSize(1);
    }

    @Test
    void db02_tenConcurrentSavesProduceSingleActivity() throws Exception {
        long userId = createHttpUser("db02_owner");
        long convId = seedDraft(userId, "db02 草稿", 1, 113.3710, 23.1330, null);

        List<Object> results = runParallel(10, () -> trySave(userId, convId, 1));
        List<Long> activityIds = results.stream()
                .filter(PlanSaveResult.class::isInstance)
                .map(r -> ((PlanSaveResult) r).activityId)
                .distinct()
                .toList();
        assertThat(activityIds).hasSize(1);

        assertThat(asUser(userId, () -> activityService.get(activityIds.get(0)).getStops())).hasSize(1);
        AiConversation conversation = conversationMapper.selectById(convId);
        assertThat(conversation.getStatus()).isEqualTo(AiConversation.STATUS_SAVED);
        assertThat(conversationMapper.selectByUser(userId)).filteredOn(c -> c.getId() == convId)
                .allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(AiConversation.STATUS_SAVED));
    }

    @Test
    void db03_injectedStopInsertErrorRollsBackEverything() throws Exception {
        doThrow(new RuntimeException("injected: stop insert failed"))
                .when(activityStopMapper).insert(any(com.campus.activity.entity.ActivityStop.class));

        long userId = createHttpUser("db03_owner");
        long beforePlaces = placeMapper.countAll();
        long convId = seedDraft(userId, "db03 草稿", 1, 113.3712, 23.1332, null);

        assertThatThrownBy(() -> asUser(userId, () -> planSaveService.save(convId, 1)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("injected: stop insert failed");

        // 全部回滚：无活动、会话未标记 SAVED、ensurePlace 期间创建的 place 也不残留
        assertThat(asUser(userId, () -> activityService.mine("all"))).isEmpty();
        AiConversation conversation = conversationMapper.selectById(convId);
        assertThat(conversation.getStatus()).isEqualTo(AiConversation.STATUS_DRAFT);
        assertThat(conversation.getSavedActivityId()).isNull();
        assertThat(placeMapper.countAll()).isEqualTo(beforePlaces);
    }

    @Test
    void db04_staleVersionRejectedFreshVersionWins() throws Exception {
        long userId = createHttpUser("db04_owner");
        String auth = loginAuth("db04_owner");

        // HTTP：升级到 v2 后，带过期版本保存 → 409；正确版本 → 成功；SAVED 后再带过期版本 → 幂等
        long convId = seedDraft(userId, "db04 草稿", 1, 113.3714, 23.1334, null);
        conversationMapper.updatePlanResult(convId, freshPlanJson(userId, "db04 草稿"), 2, AiConversation.STATUS_DRAFT);

        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        MvcResult saved = mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(saved.getResponse().getContentAsString())
                .path("data").path("activityId").asLong();
        mockMvc.perform(post("/api/assistant/chats/" + convId + "/plan/save")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedPlanVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activityId").value(activityId));

        // 服务层并发：expected=1 与 expected=2 竞争。
        // 设计语义（计划 C1）：fresh 先赢则过期线程幂等返回同一活动；stale 先到则 409——两种结局都"旧版不可能覆盖新版"。
        long convId2 = seedDraft(userId, "db04 草稿2", 2, 113.3715, 23.1335, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Object> stale = pool.submit(() -> {
                start.await();
                return trySave(userId, convId2, 1);
            });
            Future<Object> fresh = pool.submit(() -> {
                start.await();
                return trySave(userId, convId2, 2);
            });
            start.countDown();
            Object staleResult = stale.get(60, TimeUnit.SECONDS);
            Object freshResult = fresh.get(60, TimeUnit.SECONDS);
            List<Object> all = List.of(staleResult, freshResult);
            assertThat(all).anyMatch(PlanSaveResult.class::isInstance);
            // 不变式 1：至多一个活动被创建（不可能 stale 建一个、fresh 再建一个）
            List<Long> successIds = all.stream().filter(PlanSaveResult.class::isInstance)
                    .map(r -> ((PlanSaveResult) r).activityId).distinct().toList();
            assertThat(successIds).hasSize(1);
            // 不变式 2：出现冲突时只能是过期版本被拒绝
            all.stream().filter(r -> r instanceof String)
                    .forEach(r -> assertThat((String) r).contains("草稿已更新"));
            // 不变式 3：落库的活动必须来自 v2 草稿（旧版覆盖不可能发生）
            assertThat(asUser(userId, () -> activityService.get(successIds.get(0)).getTitle()))
                    .isEqualTo("db04 草稿2");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void db05_concurrentNearCoordinateCreatesFuseToOnePlace() throws Exception {
        long userId = createHttpUser("db05_owner");

        long before = placeMapper.countAll();
        long finalBefore = before;
        List<PlaceCreateResponse> results = runParallel(10, () ->
                asUser(userId, () -> placeService.createPlace(createPlaceReq(
                        "db05 孤儿点", new BigDecimal("113.3720"), new BigDecimal("23.1340"), false))));

        long createdCount = results.stream().filter(PlaceCreateResponse::isCreated).count();
        assertThat(createdCount).isEqualTo(1);
        long createdId = results.stream().filter(PlaceCreateResponse::isCreated)
                .findFirst().orElseThrow().getPlace().getId();
        for (PlaceCreateResponse result : results) {
            if (!result.isCreated()) {
                assertThat(result.getCandidates()).isNotEmpty();
                assertThat(result.getCandidates().get(0).getId()).isEqualTo(createdId);
            }
        }
        assertThat(placeMapper.countAll()).isEqualTo(finalBefore + 1);
    }

    @Test
    void db06_updateReplacesStopsWithContiguousSeq() throws Exception {
        long userId = createHttpUser("db06_owner");
        String auth = loginAuth("db06_owner");
        long placeId = asUser(userId, () -> placeService.createPlace(placeReq("db06 场馆", 113.3716, 23.1336)).getPlace().getId());

        long activityId = createActivity(auth, placeId, "db06 三站活动",
                stopJson("第一站", 113.3716, 23.1336, 0),
                stopJson("第二站", 113.3726, 23.1346, 60),
                stopJson("第三站", 113.3736, 23.1356, 120));
        List<ActivityStopItem> before = asUser(userId, () -> activityService.get(activityId).getStops());
        assertThat(before).extracting(ActivityStopItem::getName).containsExactly("第一站", "第二站", "第三站");

        mockMvc.perform(put("/api/activities/" + activityId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateStopsJson(placeId, "新版第一站", "新版第二站")))
                .andExpect(status().isOk());

        List<ActivityStopItem> after = asUser(userId, () -> activityService.get(activityId).getStops());
        assertThat(after).hasSize(2);
        assertThat(after).extracting(ActivityStopItem::getName).containsExactly("新版第一站", "新版第二站");
        assertThat(after).extracting(ActivityStopItem::getSeq).containsExactly(1, 2);
    }

    @Test
    void db07_invalidStopsRejectedWithoutDirtyData() throws Exception {
        long userId = createHttpUser("db07_owner");
        String auth = loginAuth("db07_owner");
        long placeId = asUser(userId, () -> placeService.createPlace(placeReq("db07 场馆", 113.3717, 23.1337)).getPlace().getId());

        // 站点时间早于活动开始
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createActivityJson(placeId, "早于开始",
                                "  {\"name\":\"早站\",\"longitude\":113.371,\"latitude\":23.133," +
                                        "\"startTime\":\"" + pastStopText() + "\",\"endTime\":\"" + startText() + "\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        // 缺 placeId 与坐标
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createActivityJson(placeId, "缺坐标", "  {\"name\":\"幽灵站\"}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        // 9 站超上限（字段校验）
        mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nineStopsJson(placeId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        assertThat(asUser(userId, () -> activityService.mine("all"))).isEmpty();
    }

    @Test
    void db08_singleStopLegacyFlowBackwardCompatible() throws Exception {
        long userId = createHttpUser("db08_owner");
        String auth = loginAuth("db08_owner");
        long placeId = asUser(userId, () -> placeService.createPlace(placeReq("db08 场馆", 113.3719, 23.1339)).getPlace().getId());

        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacyJson(placeId)))
                .andExpect(status().isOk())
                .andReturn();
        long activityId = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();

        assertThat(asUser(userId, () -> activityService.get(activityId).getStops())).isEmpty();
        mockMvc.perform(put("/api/activities/" + activityId)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"db08 已改名\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("db08 已改名"));
        assertThat(asUser(userId, () -> activityService.get(activityId).getStops())).isEmpty();
        mockMvc.perform(get("/api/activities/mine").header("Authorization", auth))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    // ---------- 脚手架 ----------

    /** 并发执行同一任务：结果统一为 PlanSaveResult（成功）或 String（冲突信息） */
    private <T> List<T> runParallel(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private record PlanSaveResult(long activityId) {
    }

    private Object trySave(long userId, long convId, int expectedVersion) {
        try {
            PlanSaveResponse saved = asUser(userId, () -> planSaveService.save(convId, expectedVersion));
            return new PlanSaveResult(saved.getActivityId());
        } catch (BusinessException ex) {
            return ex.getMessage() == null ? "" : ex.getMessage();
        }
    }

    /** 在当前线程注入指定用户的 SecurityContext 执行（SecurityUtils.getUserId 生效） */
    private <T> T asUser(long userId, Supplier<T> call) {
        LoginUser user = new LoginUser();
        user.setId(userId);
        user.setUsername("tester" + userId);
        user.setNickname("并发用户" + userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, List.of()));
        try {
            return call.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long createHttpUser(String username) throws Exception {
        register(username);
        return userService.getByUsername(username).getId();
    }

    private void register(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest request = new com.campus.auth.dto.RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", forwardedFor())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private String loginAuth(String username) throws Exception {
        com.campus.auth.dto.LoginRequest request = new com.campus.auth.dto.LoginRequest();
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
        return "10.118." + LOGIN_IP_SEQ.getAndIncrement() + ".7";
    }

    /** 服务端直接种子一条 validated 后的 DRAFT 会话（不经 LLM），返回 conversationId */
    private long seedDraft(long userId, String title, int version, Long placeId) throws Exception {
        return seedDraft(userId, title, version, 113.3711, 23.1331, placeId);
    }

    private long seedDraft(long userId, String title, int version,
                           double longitude, double latitude, Long placeId) throws Exception {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(userId);
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);

        PlanDto plan = new PlanDto();
        plan.setTitle(title);
        plan.setCategory("OTHER");
        plan.setSummary("并发用例草稿");
        LocalDateTime start = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(3));
        plan.setMaxMembers(20);
        PlanStop stop = new PlanStop();
        stop.setName(title + " 站点");
        stop.setPlaceId(placeId);
        if (placeId == null) {
            stop.setLongitude(longitude);
            stop.setLatitude(latitude);
        }
        stop.setStartTime(start);
        stop.setEndTime(start.plusMinutes(60));
        stop.setTransportMode("WALK");
        plan.setStops(new ArrayList<>(List.of(stop)));
        plan = planValidator.validate(plan, userId);

        conversationMapper.updatePlanResult(conversation.getId(),
                objectMapper.writeValueAsString(plan), version, AiConversation.STATUS_DRAFT);
        return conversation.getId();
    }

    private String freshPlanJson(long userId, String title) throws Exception {
        PlanDto plan = new PlanDto();
        plan.setTitle(title);
        plan.setCategory("OTHER");
        LocalDateTime start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        plan.setStartTime(start);
        plan.setEndTime(start.plusHours(3));
        plan.setMaxMembers(20);
        PlanStop stop = new PlanStop();
        stop.setName(title + " 新站点");
        stop.setLongitude(113.3713);
        stop.setLatitude(23.1333);
        stop.setStartTime(start);
        stop.setEndTime(start.plusMinutes(90));
        stop.setTransportMode("WALK");
        plan.setStops(new ArrayList<>(List.of(stop)));
        return objectMapper.writeValueAsString(planValidator.validate(plan, userId));
    }

    /** 每个用例独立坐标（同容器跨方法共享 place 表，5m 查重会把同坐标用例撞成 pendingDuplicate） */
    private CreatePlaceRequest placeReq(String name, double longitude, double latitude) {
        return createPlaceReq(name, BigDecimal.valueOf(longitude), BigDecimal.valueOf(latitude), false);
    }

    private CreatePlaceRequest createPlaceReq(String name, BigDecimal longitude, BigDecimal latitude, boolean force) {
        CreatePlaceRequest request = new CreatePlaceRequest();
        request.setName(name);
        request.setCategoryId(8L);
        request.setLongitude(longitude);
        request.setLatitude(latitude);
        request.setConfirmNew(force);
        return request;
    }

    private long createActivity(String auth, long placeId, String title, String... stopsJson) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createActivityJson(placeId, title, stopsJson)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
    }

    private String createActivityJson(long placeId, String title, String... stopsJson) {
        LocalDateTime start = LocalDateTime.now().plusHours(6).withSecond(0).withNano(0);
        StringBuilder stops = new StringBuilder();
        for (String stop : stopsJson) {
            if (stops.length() > 0) {
                stops.append(',');
            }
            stops.append(stop.trim().stripLeading());
        }
        return """
                {"placeId":%d,"title":"%s","description":"DB 用例","category":"OTHER",
                 "startTime":"%s","endTime":"%s","maxMembers":20,
                 "stops":[%s]}
                """.formatted(placeId, title, start.format(TS), start.plusHours(3).format(TS), stops);
    }

    private String stopJson(String name, double longitude, double latitude, int delayMinutes) {
        LocalDateTime start = LocalDateTime.now().plusHours(6).withSecond(0).withNano(0).plusMinutes(delayMinutes);
        return """
                 {"name":"%s","longitude":%s,"latitude":%s,"startTime":"%s","endTime":"%s","transportMode":"WALK"}
                """.formatted(name, longitude, latitude, start.format(TS), start.plusMinutes(30).format(TS));
    }

    private String updateStopsJson(long placeId, String... names) {
        StringBuilder stops = new StringBuilder();
        int seq = 0;
        for (String name : names) {
            if (seq > 0) {
                stops.append(',');
            }
            LocalDateTime start = LocalDateTime.now().plusHours(6).withSecond(0).withNano(0).plusMinutes(30L * seq);
            stops.append("""
                     {"name":"%s","longitude":%s,"latitude":%s,"startTime":"%s","endTime":"%s","transportMode":"WALK"}
                    """.formatted(name, 113.3730 + 0.0005 * seq, 23.1340 + 0.0005 * seq,
                    start.format(TS), start.plusMinutes(30).format(TS)));
            seq++;
        }
        LocalDateTime start = LocalDateTime.now().plusHours(6).withSecond(0).withNano(0);
        return """
                {"title":"db06 两站活动","startTime":"%s","endTime":"%s","stops":[%s]}
                """.formatted(start.format(TS), start.plusHours(3).format(TS), stops);
    }

    private String nineStopsJson(long placeId) {
        StringBuilder stops = new StringBuilder();
        for (int i = 0; i < 9; i++) {
            if (i > 0) {
                stops.append(',');
            }
            LocalDateTime start = LocalDateTime.now().plusHours(6).withSecond(0).withNano(0).plusMinutes(15L * i);
            stops.append("""
                     {"name":"站%d","longitude":%s,"latitude":%s,"startTime":"%s","endTime":"%s"}
                    """.formatted(i + 1, 113.37 + 0.001 * i, 23.13 + 0.001 * i,
                    start.format(TS), start.plusMinutes(15).format(TS)));
        }
        return createActivityJson(placeId, "九站超限", stops.toString());
    }

    private String legacyJson(long placeId) {
        return createActivityJson(placeId, "db08 单站老流程");
    }

    private String startText() {
        return LocalDateTime.now().plusHours(6).withSecond(0).withNano(0).format(TS);
    }

    private String pastStopText() {
        return LocalDateTime.now().plusHours(2).withSecond(0).withNano(0).format(TS);
    }
}
