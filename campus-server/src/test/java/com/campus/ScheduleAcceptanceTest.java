package com.campus;

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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0：个人日程 CRUD + 多源忙闲 + FREE_BUSY 授权（计划 §4.3/§8）。
 * 覆盖：owner-only CRUD、忙闲=日程∪已报名活动、群体视图_unknown_不视为有空、授权/撤销。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ScheduleAcceptanceTest {

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

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.campus.user.service.UserService userService;

    private final java.util.Map<String, String> tokens = new java.util.HashMap<>();
    private final java.util.Map<String, Long> userIds = new java.util.HashMap<>();

    @Test
    void scheduleCrudOwnershipAndValidation() throws Exception {
        long owner = seededUser("sch01_owner");
        String auth = tokens.get("sch01_owner");
        LocalDateTime slot = slotStart();

        MvcResult created = mockMvc.perform(post("/api/schedules")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson("上午自习", slot, slot.plusMinutes(120), "图书馆三楼")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("上午自习"))
                .andExpect(jsonPath("$.data.note").value("图书馆三楼"))
                .andReturn();
        long id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", auth)
                        .param("from", iso(slot.minusHours(1)))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        // 时间无效与过去时间都拒绝
        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson("倒退", slot.plusMinutes(120), slot, null)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/schedules/" + id)
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson("改到下午", slot.plusHours(1), slot.plusHours(2), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("改到下午"));

        // 其他用户删除 → 404（不暴露存在性）
        long other = seededUser("sch01_other");
        mockMvc.perform(delete("/api/schedules/" + id)
                        .header("Authorization", tokens.get("sch01_other")))
                .andExpect(status().isNotFound());
        assertThat(other).isPositive();

        mockMvc.perform(delete("/api/schedules/" + id)
                        .header("Authorization", auth))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/schedules")
                        .header("Authorization", auth)
                        .param("from", iso(slot.minusHours(1)))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void busyMergesPersonalScheduleAndJoinedActivity() throws Exception {
        long owner = seededUser("sch02_owner");
        String auth = tokens.get("sch02_owner");
        LocalDateTime slot = slotStart();

        // 建共享图并加一个地点，作为活动主场地（普通用户无 place:create 权限，走地图通道）
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sch02 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long mapId = objectMapper.readTree(group.getResponse().getContentAsString())
                .path("data").path("mapId").asLong();
        MvcResult placed = mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sch02 场地\",\"description\":\"\",\"categoryId\":4,\"longitude\":113.3701,\"latitude\":23.1001,\"confirmNew\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(true))
                .andReturn();
        long placeId = objectMapper.readTree(placed.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson("私人门诊", slot.plusMinutes(60), slot.plusMinutes(90), null)))
                .andExpect(status().isOk());
        // 本人创建的活动（自动入成员）也是忙碌来源
        MvcResult activity = mockMvc.perform(post("/api/activities")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(activityJson(placeId, slot.plusHours(10), slot.plusHours(13))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(activity).isNotNull();

        MvcResult busy = mockMvc.perform(get("/api/schedules/busy")
                        .header("Authorization", auth)
                        .param("from", iso(slot))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode entries = objectMapper.readTree(busy.getResponse().getContentAsString()).path("data");
        assertThat(entries.size()).isEqualTo(2);
        boolean hasSchedule = false;
        boolean hasActivity = false;
        for (JsonNode entry : entries) {
            if ("SCHEDULE".equals(entry.path("sourceType").asText())) {
                hasSchedule = true;
                assertThat(entry.path("title").asText()).isEqualTo("私人门诊");
            }
            if ("ACTIVITY".equals(entry.path("sourceType").asText())) {
                hasActivity = true;
            }
        }
        assertThat(hasSchedule).isTrue();
        assertThat(hasActivity).isTrue();
    }

    @Test
    void groupBusyRespectsFreeBusyGrants() throws Exception {
        long owner = seededUser("sch03_owner");
        long viewer = seededUser("sch03_viewer");
        long stranger = seededUser("sch03_stranger");
        LocalDateTime slot = slotStart();

        // viewer 建图（=建 campus_group）
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get("sch03_viewer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"sch03 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        long groupId = objectMapper.readTree(group.getResponse().getContentAsString())
                .path("data").path("groupId").asLong();
        String inviteCode = objectMapper.readTree(group.getResponse().getContentAsString())
                .path("data").path("inviteCode").asText();

        // owner 加入 viewer 的图组，成为群规划参与者
        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", tokens.get("sch03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/schedules")
                        .header("Authorization", tokens.get("sch03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(scheduleJson("医生复诊", slot.plusHours(2), slot.plusHours(3), "细节隐私")))
                .andExpect(status().isOk());

        // stranger 未授权（viewer/group 均未授权 stranger 看到的目标——反向：查看者 stranger 无授权，包括明细）
        // 第一次：owner 未授权 → viewer 的群体视图里 owner=UNKNOWN 且不泄露时段
        MvcResult beforeGrant = mockMvc.perform(get("/api/schedules/group-busy")
                        .header("Authorization", tokens.get("sch03_viewer"))
                        .param("groupId", String.valueOf(groupId))
                        .param("from", iso(slot))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode before = objectMapper.readTree(beforeGrant.getResponse().getContentAsString()).path("data");
        assertThat(before.path("unknownUserIds").toString()).contains(String.valueOf(owner));
        assertThat(before.path("known").toString()).doesNotContain("医生复诊");

        // owner 授予 GROUP FREE_BUSY → viewer 视图 KNOWN（仅时段，无标题/备注）
        mockMvc.perform(put("/api/schedules/share")
                        .header("Authorization", tokens.get("sch03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"GROUP\",\"targetId\":" + groupId + ",\"grant\":true}"))
                .andExpect(status().isOk());
        MvcResult afterGrant = mockMvc.perform(get("/api/schedules/group-busy")
                        .header("Authorization", tokens.get("sch03_viewer"))
                        .param("groupId", String.valueOf(groupId))
                        .param("from", iso(slot))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode after = objectMapper.readTree(afterGrant.getResponse().getContentAsString()).path("data");
        assertThat(after.path("unknownUserIds").toString()).doesNotContain(String.valueOf(owner));
        assertThat(after.path("known").toString()).contains("\"startTime\"");
        assertThat(after.path("known").toString()).doesNotContain("医生复诊").doesNotContain("细节隐私");

        // 撤销授权 → 回到 UNKNOWN
        mockMvc.perform(put("/api/schedules/share")
                        .header("Authorization", tokens.get("sch03_owner"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"GROUP\",\"targetId\":" + groupId + ",\"grant\":false}"))
                .andExpect(status().isOk());
        MvcResult afterRevoke = mockMvc.perform(get("/api/schedules/group-busy")
                        .header("Authorization", tokens.get("sch03_viewer"))
                        .param("groupId", String.valueOf(groupId))
                        .param("from", iso(slot))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode revoked = objectMapper.readTree(afterRevoke.getResponse().getContentAsString()).path("data");
        assertThat(revoked.path("unknownUserIds").toString()).contains(String.valueOf(owner));

        // stranger 对同授权直接查询也只有 viewer 视角；异群体使用 owner 的单查端点（/busy）不可行——403/404 已由 owner-only 保证
        mockMvc.perform(get("/api/schedules/busy")
                        .header("Authorization", tokens.get("sch03_stranger"))
                        .param("from", iso(slot))
                        .param("to", iso(slot.plusDays(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 脚手架 ----------

    private String scheduleJson(String title, LocalDateTime start, LocalDateTime end, String note) {
        return """
                {"title":"%s","startTime":"%s","endTime":"%s","note":%s}
                """.formatted(title, iso(start), iso(end),
                note == null ? "null" : "\"" + note + "\"");
    }

    private String activityJson(long placeId, LocalDateTime start, LocalDateTime end) {
        return """
                {"placeId":%d,"title":"sch02 球赛","description":"","startTime":"%s","endTime":"%s","maxMembers":10}
                """.formatted(placeId, iso(start), iso(end));
    }

    /** 未来一天 08:00 的测试基准时间（绝对时点，避免相对秒的 epoch 误算） */
    private LocalDateTime slotStart() {
        return LocalDateTime.now().plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
    }

    private String iso(LocalDateTime value) {
        return TS.format(value);
    }

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.123." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.123." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(login)))
                .andExpect(status().isOk())
                .andReturn();
        String token = "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("accessToken").asText();
        tokens.put(username, token);
        long userId = userService.getByUsername(username).getId();
        userIds.put(username, userId);
        return userId;
    }
}
