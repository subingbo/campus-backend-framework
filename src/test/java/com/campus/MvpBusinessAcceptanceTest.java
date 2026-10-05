package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.campus.post.dto.CreateCommentRequest;
import com.campus.post.dto.CreatePostRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MvpBusinessAcceptanceTest {

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
    void mvpBusinessFlow() throws Exception {
        String username = "mvp_" + System.nanoTime();
        registerUser(username);
        TokenResponse tokens = login(username, "password1");
        String auth = "Bearer " + tokens.getAccessToken();

        mockMvc.perform(get("/api/users/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));

        mockMvc.perform(put("/api/users/me")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"MVP用户\",\"avatar\":\"https://example.com/a.png\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("MVP用户"));

        String placeBody = """
                {
                  "name": "北区篮球场",
                  "description": "学校北区篮球场",
                  "categoryId": 3,
                  "longitude": 113.3841000,
                  "latitude": 23.0456000,
                  "confirmNew": true
                }
                """;
        MvcResult placeResult = mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(true))
                .andReturn();
        long placeId = objectMapper.readTree(placeResult.getResponse().getContentAsString())
                .path("data").path("place").path("id").asLong();

        String duplicateBody = """
                {
                  "name": "另一个名字",
                  "categoryId": 3,
                  "longitude": 113.3841001,
                  "latitude": 23.0456001,
                  "confirmNew": false
                }
                """;
        mockMvc.perform(post("/api/places")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(duplicateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.created").value(false))
                .andExpect(jsonPath("$.data.candidates[0].id").value(placeId));

        mockMvc.perform(get("/api/places/nearby")
                        .header("Authorization", auth)
                        .param("longitude", "113.3841000")
                        .param("latitude", "23.0456000")
                        .param("radius", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(placeId));

        CreatePostRequest postRequest = new CreatePostRequest();
        postRequest.setPlaceId(placeId);
        postRequest.setContent("今晚有人打球吗？");
        MvcResult postResult = mockMvc.perform(post("/api/posts")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(postRequest)))
                .andExpect(status().isOk())
                .andReturn();
        long postId = objectMapper.readTree(postResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        CreateCommentRequest comment = new CreateCommentRequest();
        comment.setContent("我可以，几点？");
        MvcResult commentResult = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(comment)))
                .andExpect(status().isOk())
                .andReturn();
        long parentCommentId = objectMapper.readTree(commentResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        CreateCommentRequest reply = new CreateCommentRequest();
        reply.setParentId(parentCommentId);
        reply.setContent("晚上八点");
        MvcResult replyResult = mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reply)))
                .andExpect(status().isOk())
                .andReturn();
        long replyId = objectMapper.readTree(replyResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        CreateCommentRequest thirdLevel = new CreateCommentRequest();
        thirdLevel.setParentId(replyId);
        thirdLevel.setContent("三级评论应被拒绝");
        mockMvc.perform(post("/api/posts/" + postId + "/comments")
                        .header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(thirdLevel)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/posts/" + postId + "/like").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));

        mockMvc.perform(delete("/api/posts/" + postId + "/like").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false));

        MvcResult meResult = mockMvc.perform(get("/api/users/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn();
        long userId = objectMapper.readTree(meResult.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        mockMvc.perform(get("/api/users/" + userId).header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postCount").value(1));

        mockMvc.perform(get("/api/search")
                        .header("Authorization", auth)
                        .param("keyword", "篮球场")
                        .param("type", "place"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.places[0].name").value("北区篮球场"));

        mockMvc.perform(get("/api/places/" + placeId + "/feed").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.posts.list[0].content").value("今晚有人打球吗？"));

        assertThat(placeId).isPositive();
        assertThat(postId).isPositive();
    }

    private void registerUser(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname("测试");
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
}
