package com.campus;

import com.campus.auth.dto.LoginRequest;
import com.campus.auth.dto.RefreshRequest;
import com.campus.auth.dto.RegisterRequest;
import com.campus.auth.dto.TokenResponse;
import com.campus.security.jwt.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
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

import java.nio.charset.StandardCharsets;
import java.util.Date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.profiles.active=test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InfrastructureAcceptanceTest {

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

    @Value("${campus.jwt.secret}")
    private String jwtSecret;

    @Test
    void case1_unauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void case3_expiredAccessToken() throws Exception {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        String tokenId = "expiredtokenid001";
        String expired = Jwts.builder()
                .subject("1")
                .claim(JwtTokenProvider.CLAIM_USER_ID, 1L)
                .claim(JwtTokenProvider.CLAIM_USERNAME, "admin")
                .claim(JwtTokenProvider.CLAIM_TOKEN_ID, tokenId)
                .issuedAt(past)
                .expiration(past)
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void case2_invalidToken() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void mainFlow_registerLoginMe() throws Exception {
        String username = "user_" + System.nanoTime();
        registerUser(username);

        TokenResponse tokens = login(username, "password1");
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.roles[0]").value("USER"));
    }

    @Test
    void case4_forbiddenForUserOnAdminDelete() throws Exception {
        String username = "user_" + System.nanoTime();
        registerUser(username);
        TokenResponse tokens = login(username, "password1");

        mockMvc.perform(delete("/api/admin/user/1")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void case5_adminCanDelete() throws Exception {
        TokenResponse tokens = login("admin", "admin123");
        mockMvc.perform(delete("/api/admin/user/999")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void case6_refreshTokenRotation() throws Exception {
        String username = "user_" + System.nanoTime();
        registerUser(username);
        TokenResponse tokens = login(username, "password1");

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken(tokens.getRefreshToken());
        MvcResult refreshResult = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode body = objectMapper.readTree(refreshResult.getResponse().getContentAsString());
        String newAccess = body.path("data").path("accessToken").asText();
        String newRefresh = body.path("data").path("refreshToken").asText();
        assertThat(newAccess).isNotBlank();
        assertThat(newRefresh).isNotEqualTo(tokens.getRefreshToken());

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk());
    }

    @Test
    void case7_revokedRefreshToken() throws Exception {
        String username = "user_" + System.nanoTime();
        registerUser(username);
        TokenResponse tokens = login(username, "password1");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isOk());

        RefreshRequest refreshRequest = new RefreshRequest();
        refreshRequest.setRefreshToken(tokens.getRefreshToken());
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void case8_rateLimitOnPosts() throws Exception {
        String username = "user_" + System.nanoTime();
        registerUser(username);
        TokenResponse tokens = login(username, "password1");

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/posts")
                            .header("Authorization", "Bearer " + tokens.getAccessToken()))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/posts")
                        .header("Authorization", "Bearer " + tokens.getAccessToken()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429));
    }

    private void registerUser(String username) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setPassword("password1");
        request.setNickname("测试");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private TokenResponse login(String username, String password) throws Exception {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new TokenResponse(
                data.path("accessToken").asText(),
                data.path("refreshToken").asText(),
                data.path("expiresIn").asLong()
        );
    }
}
