package com.campus;

import com.campus.ai.service.GroupMapContextService;
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

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0.5：群体共建地图上下文与地点语义检索。直接驱动 AgentToolkit（不经 LLM）：
 * 备注命中检索、群语境只读本群地图、备注保留地图对应关系、跨群隔离、
 * 个人语境范围、planningSourceVersion 指纹。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class GroupMapContextAcceptanceTest {

    private static final AtomicInteger LOGIN_IP_SEQ = new AtomicInteger(41);

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
    private AgentToolkit toolkit;
    @Autowired
    private GroupMapContextService groupMapContextService;
    @Autowired
    private com.campus.user.service.UserService userService;

    private final Map<String, String> tokens = new java.util.HashMap<>();
    private final Map<String, Long> ids = new java.util.HashMap<>();

    @Test
    void groupToolsExposeOnlyOwnGroupAndMatchNotes() throws Exception {
        long ownerA = seededUser("p05a_owner");
        long memberA = seededUser("p05a_member");
        seededUser("p05b_owner");
        GroupData a = createGroup("p05a", "p05a_owner");
        GroupData b = createGroup("p05b", "p05b_owner");
        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", tokens.get("p05a_member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + a.invite + "\"}"))
                .andExpect(status().isOk());

        long library = addPlace("p05a_owner", a.mapId, "图书馆", "校园主馆", 2,
                113.37610, 23.09910);
        long coffee = addPlace("p05a_owner", a.mapId, "栈咖啡", "校门口咖啡店", 1,
                113.37810, 23.10210);
        addPlace("p05a_owner", a.mapId, "东操场", "标准操场", 3, 113.38010, 23.10510);
        addComment("p05a_owner", a.mapId, library, "适合安静学习，插座多，能坐一整天");
        addComment("p05a_owner", a.mapId, coffee, "适合四个人讨论，带电脑很方便");
        addComment("p05a_owner", a.mapId, coffee, "下午人少");
        addComment("p05a_owner", a.mapId, library, "适合运动");
        long secret = addPlace("p05b_owner", b.mapId, "B群专属讨论室", "群内讨论地点", 1,
                113.39010, 23.11010);
        addComment("p05b_owner", b.mapId, secret, "B 群机密：讨论比赛项目专用");

        AgentToolkit.setGroupContext(memberA, a.groupId);
        try {
            JsonNode maps = executeAsJson(AgentToolkit.TOOL_GET_GROUP_MAP_CONTEXT, "{}");
            assertThat(maps.path("source").asText()).isEqualTo("GROUP_MAP");
            assertThat(maps.path("maps").size()).isEqualTo(1);
            assertThat(maps.path("maps").get(0).path("mapId").asLong()).isEqualTo(a.mapId);
            assertThat(maps.path("maps").get(0).path("placeCount").asInt()).isEqualTo(3);

            // 场景 A：备注命中——"讨论"通过群内备注检索出咖啡店（名称不含"讨论"）
            JsonNode search = executeAsJson(AgentToolkit.TOOL_SEARCH_GROUP_MAP_PLACES,
                    "{\"keyword\":\"讨论\"}");
            assertThat(search.path("source").asText()).isEqualTo("GROUP_MAP");
            assertThat(search.path("total").asInt()).isEqualTo(1);
            assertThat(search.path("results").get(0).path("name").asText()).isEqualTo("栈咖啡");
            assertThat(search.path("results").get(0).path("mapIds").get(0).asLong()).isEqualTo(a.mapId);
            assertThat(search.path("results").get(0).path("notes").get(0).path("mapId").asLong())
                    .isEqualTo(a.mapId);
            assertThat(search.path("results").get(0).path("notes").get(0).path("content").asText())
                    .contains("带电脑很方便");

            JsonNode place = executeAsJson(AgentToolkit.TOOL_GET_PLACE_CONTEXT,
                    "{\"placeId\":" + coffee + "}");
            assertThat(place.path("source").asText()).isEqualTo("GROUP_MAP");
            assertThat(place.path("place").path("name").asText()).isEqualTo("栈咖啡");
            assertThat(place.path("place").path("notes").size()).isEqualTo(1);
            assertThat(place.path("place").path("notes").get(0).path("content").asText())
                    .contains("适合四个人讨论");

            // 地图限定查看
            JsonNode scoped = executeAsJson(AgentToolkit.TOOL_GET_PLACE_CONTEXT,
                    "{\"placeId\":" + coffee + ",\"mapId\":" + a.mapId + "}");
            assertThat(scoped.path("place").path("placeId").asLong()).isEqualTo(coffee);

            // 跨群隔离：B 群地点与备注在 A 群语境必须读不到
            JsonNode leaked = executeAsJson(AgentToolkit.TOOL_GET_PLACE_CONTEXT,
                    "{\"placeId\":" + secret + "}");
            assertThat(leaked.path("error").asText("")).isNotBlank();
            assertThat(leaked.toString()).doesNotContain("B 群机密");

            JsonNode cross = executeAsJson(AgentToolkit.TOOL_SEARCH_GROUP_MAP_PLACES,
                    "{\"keyword\":\"机密\"}");
            assertThat(cross.path("total").asInt()).isEqualTo(0);
        } finally {
            AgentToolkit.clearGroupContext();
        }

        // 指纹：地图/地点集合变化必须能被检测（保存链路的 planningSourceVersion 依据）
        String before = groupMapContextService.fingerprint(List.of(a.mapId));
        assertThat(groupMapContextService.fingerprint(List.of(a.mapId))).isEqualTo(before);
        addPlace("p05a_owner", a.mapId, "新地标", "p05d 后加入地点", 8, 113.40210, 23.12210);
        assertThat(groupMapContextService.fingerprint(List.of(a.mapId))).isNotEqualTo(before);
        assertThatThrownBy(() -> groupMapContextService.fingerprint(List.of(999999L)))
                .isInstanceOf(com.campus.common.exception.BusinessException.class);
    }

    @Test
    void personalContextCoversUsersOwnMapsOnly() throws Exception {
        long ownerB = seededUser("p05c_owner");
        GroupData b = createGroup("p05c", "p05c_owner");
        long secret = addPlace("p05c_owner", b.mapId, "p05c咖啡", "私有地图地点", 1,
                113.39610, 23.11610);
        addComment("p05c_owner", b.mapId, secret, "个人常去，环境好");

        // 个人语境：不注入群上下文（直连工具需要填充 SecurityContext）
        loginAsSecurity("p05c_owner");
        try {
            JsonNode maps = executeAsJson(AgentToolkit.TOOL_GET_GROUP_MAP_CONTEXT, "{}");
            assertThat(maps.path("source").asText()).isEqualTo("PERSONAL_MAP");
            assertThat(maps.toString()).contains("p05c");

            JsonNode search = executeAsJson(AgentToolkit.TOOL_SEARCH_GROUP_MAP_PLACES,
                    "{\"keyword\":\"环境好\"}");
            assertThat(search.path("source").asText()).isEqualTo("PERSONAL_MAP");
            assertThat(search.path("results").toString()).contains("p05c咖啡");
        } finally {
            clearSecurity();
        }

        // 非成员在群语境读不到该地点：返回 error JSON 且无数据泄漏
        long outsider = seededUser("p05c_outsider");
        AgentToolkit.setGroupContext(outsider, b.groupId);
        try {
            JsonNode leaked = executeAsJson(AgentToolkit.TOOL_GET_PLACE_CONTEXT,
                    "{\"placeId\":" + secret + "}");
            assertThat(leaked.path("error").asText("")).isNotBlank();
            assertThat(leaked.toString()).doesNotContain("环境好");
            JsonNode searchLeak = executeAsJson(AgentToolkit.TOOL_SEARCH_GROUP_MAP_PLACES,
                    "{\"keyword\":\"环境好\"}");
            assertThat(searchLeak.path("total").asInt()).isEqualTo(0);
        } finally {
            AgentToolkit.clearGroupContext();
        }
    }

    // ---------- 脚手架 ----------

    private record GroupData(long groupId, long mapId, String invite) {
    }

    /** 直连工具的个人语境分支需要 SecurityContext（mockMvc 请求之外） */
    private void loginAsSecurity(String username) {
        com.campus.security.user.LoginUser loginUser = new com.campus.security.user.LoginUser();
        loginUser.setId(ids.get(username));
        loginUser.setUsername(username);
        loginUser.setNickname(username);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        loginUser, null, loginUser.getAuthorities()));
    }

    private void clearSecurity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private JsonNode executeAsJson(String tool, String argsJson) throws Exception {
        return objectMapper.readTree(toolkit.execute(tool, argsJson));
    }

    private GroupData createGroup(String tag, String ownerTokenKey) throws Exception {
        MvcResult group = mockMvc.perform(post("/api/groups")
                        .header("Authorization", tokens.get(ownerTokenKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + tag + " 图\",\"description\":\"d\",\"visibility\":\"MEMBERS\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(group.getResponse().getContentAsString()).path("data");
        return new GroupData(data.path("groupId").asLong(), data.path("mapId").asLong(),
                data.path("inviteCode").asText());
    }

    private long addPlace(String callerTokenKey, long mapId, String name, String description,
                          long categoryId, double lng, double lat) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", tokens.get(callerTokenKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + description
                                + "\",\"categoryId\":" + categoryId
                                + ",\"longitude\":" + lng + ",\"latitude\":" + lat
                                + ",\"confirmNew\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("added").asBoolean()).isTrue();
        return data.path("place").path("id").asLong();
    }

    private void addComment(String callerTokenKey, long mapId, long placeId, String content) throws Exception {
        mockMvc.perform(post("/api/maps/" + mapId + "/places/" + placeId + "/comments")
                        .header("Authorization", tokens.get(callerTokenKey))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk());
    }

    private long seededUser(String username) throws Exception {
        com.campus.auth.dto.RegisterRequest register = new com.campus.auth.dto.RegisterRequest();
        register.setUsername(username);
        register.setPassword("password1");
        register.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .header("X-Forwarded-For", "10.126." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isOk());
        com.campus.auth.dto.LoginRequest login = new com.campus.auth.dto.LoginRequest();
        login.setUsername(username);
        login.setPassword("password1");
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "10.126." + LOGIN_IP_SEQ.getAndIncrement() + ".7")
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
