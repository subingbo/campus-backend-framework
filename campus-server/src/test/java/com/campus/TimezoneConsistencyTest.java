package com.campus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时区一致性：Testcontainers MySQL 会话强制为 Asia/Shanghai（+08:00），
 * Java LocalDateTime.now() 与 DB NOW() 必须落在同一墙钟，避免 UTC 部署偏 8 小时。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Testcontainers(disabledWithoutDocker = true)
class TimezoneConsistencyTest {

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
        registry.add("spring.datasource.url", TimezoneConsistencyTest::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
    }

    private static String jdbcUrl() {
        String base = MYSQL.getJdbcUrl();
        return base + (base.contains("?") ? "&" : "?")
                + "connectionTimeZone=Asia/Shanghai&forceConnectionTimeZoneToSession=true";
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void dbNowAndJavaNowShareTheSameWallClock() {
        String sessionZone = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);
        assertThat(sessionZone).isIn("+08:00", "Asia/Shanghai");

        LocalDateTime dbNow = jdbcTemplate.queryForObject("SELECT NOW()", LocalDateTime.class);
        assertThat(Math.abs(Duration.between(LocalDateTime.now(), dbNow).getSeconds())).isLessThan(2);

        jdbcTemplate.update("INSERT INTO notification (user_id, type, title, created_at) VALUES (?, ?, ?, NOW())",
                999_001L, "TEST", "timezone");
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        LocalDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM notification WHERE id = ?", LocalDateTime.class, id);
        assertThat(Math.abs(Duration.between(LocalDateTime.now(), createdAt).getSeconds())).isLessThan(2);

        jdbcTemplate.update("UPDATE notification SET read_at = NOW() WHERE id = ?", id);
        LocalDateTime readAt = jdbcTemplate.queryForObject(
                "SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, id);
        assertThat(readAt).isAfterOrEqualTo(createdAt);
    }
}
