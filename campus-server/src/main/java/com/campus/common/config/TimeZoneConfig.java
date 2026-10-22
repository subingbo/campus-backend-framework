package com.campus.common.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

/**
 * 统一应用时区：Java LocalDateTime 与 MySQL 会话 NOW() 必须使用同一墙钟。
 * JDBC 另行配置 connectionTimeZone + forceConnectionTimeZoneToSession；
 * 这里负责 JVM 侧，并在启动时打印一次诊断，避免部署到 UTC 容器后静默偏 8 小时。
 */
@Slf4j
@Configuration
public class TimeZoneConfig {

    @Value("${campus.timezone:Asia/Shanghai}")
    private String timezone;

    @PostConstruct
    public void configureJvmTimeZone() {
        ZoneId zone = ZoneId.of(timezone);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        log.info("应用时区已设置：{}（JVM default={}）", zone, TimeZone.getDefault().getID());
    }

    @Bean
    public ApplicationRunner timeZoneDiagnostics(JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                String sessionZone = jdbcTemplate.queryForObject("SELECT @@session.time_zone", String.class);
                LocalDateTime dbNow = jdbcTemplate.queryForObject("SELECT NOW()", LocalDateTime.class);
                long skewSeconds = Math.abs(java.time.Duration.between(LocalDateTime.now(), dbNow).getSeconds());
                log.info("时区诊断：JVM={} DB session={} DB NOW={} 偏差={}s",
                        TimeZone.getDefault().getID(), sessionZone, dbNow, skewSeconds);
            } catch (Exception ex) {
                log.warn("时区诊断失败（不影响启动）：{}", ex.toString());
            }
        };
    }
}
