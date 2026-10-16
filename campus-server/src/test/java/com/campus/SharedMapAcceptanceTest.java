package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SharedMapAcceptanceTest {

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

    @Test
    void sharedMapCollaborationFlow() throws Exception {
        String owner = "map_owner_" + System.nanoTime();
        String guest = "map_guest_" + System.nanoTime();
        String viewer = "map_viewer_" + System.nanoTime();
        registerUser(owner);
        registerUser(guest);
        registerUser(viewer);
        String ownerAuth = bearer(login(owner, "password1"));
        String guestAuth = bearer(login(guest, "password1"));
        String viewerAuth = bearer(login(viewer, "password1"));

        String createBody = """
                {"name":"宿舍506","description":"吃喝玩乐","visibility":"MEMBERS"}
                """;
        MvcResult createResult = mockMvc.perform(post("/api/groups")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mapId").isNumber())
                .andExpect(jsonPath("$.data.inviteCode").isNotEmpty())
                .andReturn();
        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("data");
        long mapId = createJson.path("mapId").asLong();
        String inviteCode = createJson.path("inviteCode").asText();

        mockMvc.perform(get("/api/maps/" + mapId).header("Authorization", guestAuth))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", guestAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.myRole").value("EDITOR"));

        mockMvc.perform(post("/api/groups/join")
                        .header("Authorization", viewerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviteCode\":\"" + inviteCode + "\"}"))
                .andExpect(status().isOk());

        MvcResult viewerMeResult = mockMvc.perform(get("/api/users/me").header("Authorization", viewerAuth))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode viewerMe = objectMapper.readTree(viewerMeResult.getResponse().getContentAsString()).path("data");
        long viewerId = viewerMe.path("id").asLong();

        mockMvc.perform(put("/api/maps/" + mapId + "/members/" + viewerId)
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());

        String firstPlace = """
                {
                  "name": "北门烧烤",
                  "categoryId": 2,
                  "longitude": 113.3841000,
                  "latitude": 23.0456000,
                  "confirmNew": true
                }
                """;
        MvcResult firstResult = mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstPlace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(true))
                .andReturn();
        long placeId = objectMapper.readTree(firstResult.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();

        String duplicatePlace = """
                {
                  "name": "北门烧烤店",
                  "categoryId": 2,
                  "longitude": 113.3841001,
                  "latitude": 23.0456001,
                  "confirmNew": false
                }
                """;
        mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", guestAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicatePlace))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(false))
                .andExpect(jsonPath("$.data.duplicatePending").value(true))
                .andExpect(jsonPath("$.data.candidates[0].id").value(placeId));

        mockMvc.perform(post("/api/maps/" + mapId + "/places")
                        .header("Authorization", viewerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicatePlace))
                .andExpect(status().isForbidden());

        String secondMapBody = """
                {"name":"美食小组","description":"第二地图"}
                """;
        MvcResult secondMapResult = mockMvc.perform(post("/api/groups")
                        .header("Authorization", guestAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(secondMapBody))
                .andExpect(status().isOk())
                .andReturn();
        long map2Id = objectMapper.readTree(secondMapResult.getResponse().getContentAsString())
                .path("data").path("mapId").asLong();

        mockMvc.perform(post("/api/maps/" + map2Id + "/places/" + placeId)
                        .header("Authorization", guestAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(true));

        mockMvc.perform(post("/api/maps/" + mapId + "/places/" + placeId + "/comments")
                        .header("Authorization", ownerAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"晚上人很多\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("晚上人很多"));

        mockMvc.perform(get("/api/maps/" + map2Id + "/places/" + placeId + "/comments")
                        .header("Authorization", guestAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(put("/api/places/" + placeId)
                        .header("Authorization", guestAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"北门烧烤（更新）\",\"categoryId\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("北门烧烤（更新）"));

        mockMvc.perform(delete("/api/maps/" + mapId + "/places/" + placeId)
                        .header("Authorization", ownerAuth))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/maps/" + mapId + "/places").header("Authorization", ownerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(get("/api/maps/" + map2Id + "/places").header("Authorization", guestAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    private void registerUser(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname(username);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    private TokenResponse login(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new TokenResponse(
                data.path("accessToken").asText(),
                data.path("refreshToken").asText(),
                data.path("expiresIn").asLong()
        );
    }

    private String bearer(TokenResponse tokens) {
        assertThat(tokens.getAccessToken()).isNotBlank();
        return "Bearer " + tokens.getAccessToken();
    }
}
