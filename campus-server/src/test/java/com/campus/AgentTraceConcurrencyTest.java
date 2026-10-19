package com.campus;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.mapper.AgentTraceEventMapper;
import com.campus.ai.trace.mapper.AgentTraceSessionMapper;
import com.campus.ai.trace.service.AgentTraceCleanupJob;
import com.campus.ai.trace.service.AgentTraceExportService;
import com.campus.ai.trace.store.AgentTraceWriter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace 并发 seq、1 万事件流式导出、保留策略清理。
 */
@SpringBootTest(properties = "spring.profiles.active=test")
@Testcontainers(disabledWithoutDocker = true)
class AgentTraceConcurrencyTest {

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
    private AgentTraceSessionMapper sessionMapper;
    @Autowired
    private AgentTraceEventMapper eventMapper;
    @Autowired
    private AgentTraceWriter traceWriter;
    @Autowired
    private AgentTraceExportService exportService;
    @Autowired
    private AgentTraceCleanupJob cleanupJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void parallelSessionsHaveUniqueContiguousSeq() throws Exception {
        AgentTraceSession first = newSession("RUNNING", LocalDateTime.now());
        AgentTraceSession second = newSession("RUNNING", LocalDateTime.now());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> {
                await(start);
                for (int i = 0; i < 100; i++) {
                    traceWriter.enqueue(event(first.getId(), "tool/result"));
                }
            });
            Future<?> b = pool.submit(() -> {
                await(start);
                for (int i = 0; i < 100; i++) {
                    traceWriter.enqueue(event(second.getId(), "tool/result"));
                }
            });
            start.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(traceWriter.flushNow()).isTrue();

        assertSeq(first.getId(), 100);
        assertSeq(second.getId(), 100);
    }

    @Test
    void largeSessionExportsAsStreamingJsonl() throws Exception {
        AgentTraceSession session = newSession("COMPLETED", LocalDateTime.now().minusMinutes(5));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 10_000; i++) {
            rows.add(new Object[]{session.getId(), "trace-large", (long) i, "step/end",
                    System.currentTimeMillis(), 1, 1, "SUCCESS", 1L, "{}"});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO agent_trace_event
                  (session_id, trace_id, seq, event_type, occurred_at_ms, turn_no, step_no,
                   status, duration_ms, payload_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.exportJsonl(session.getId(), 1L, out);
        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasSize(10_001);
        JsonNode meta = objectMapper.readTree(lines[0]);
        assertThat(meta.path("type").asText()).isEqualTo("trace/meta");
        assertThat(meta.path("data").path("complete").asBoolean()).isTrue();
        // 抽样校验每行都是合法 JSON（首尾 + 中间）
        for (int index : new int[]{1, 5_000, 10_000}) {
            assertThat(objectMapper.readTree(lines[index]).path("seq").asLong()).isEqualTo(index);
        }
    }

    @Test
    void cleanupMarksStaleInterruptedAndDeletesExpired() {
        AgentTraceSession stale = newSession("RUNNING", LocalDateTime.now().minusHours(3));
        assertThat(cleanupJob.markStaleRunning()).isPositive();
        AgentTraceSession interrupted = sessionMapper.selectById(stale.getId());
        assertThat(interrupted.getStatus()).isEqualTo("INTERRUPTED");
        assertThat(interrupted.getLastErrorCode()).isEqualTo("INTERRUPTED_STALE");
        assertThat(types(stale.getId())).contains("trace/partial");
        assertThat(types(stale.getId())).doesNotContain("session/end");

        AgentTraceSession expired = newSession("COMPLETED", LocalDateTime.now().minusDays(40));
        cleanupJob.deleteExpired();
        assertThat(sessionMapper.selectById(expired.getId())).isNull();
    }

    private AgentTraceSession newSession(String status, LocalDateTime startedAt) {
        AgentTraceSession session = new AgentTraceSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(1L);
        session.setConversationId(System.nanoTime());
        session.setTriggerSource("chat");
        session.setProvider("test");
        session.setModel("test-model");
        session.setStatus(status);
        session.setStartedAt(startedAt);
        session.setEventCount(0);
        session.setToolCallCount(0);
        session.setErrorCount(0);
        session.setDroppedCount(0);
        session.setNextSeq(0L);
        sessionMapper.insert(session);
        return session;
    }

    private TraceEventRecord event(String sessionId, String type) {
        return TraceEventRecord.builder()
                .sessionId(sessionId)
                .traceId(UUID.randomUUID().toString())
                .occurredAtMs(System.currentTimeMillis())
                .turn(1)
                .step(1)
                .type(type)
                .status("SUCCESS")
                .data(Map.of("round", 1))
                .build();
    }

    private void assertSeq(String sessionId, int expected) {
        List<AgentTraceEvent> events = eventMapper.selectExportBatch(sessionId, 0L, 1000);
        assertThat(events).hasSize(expected);
        long seq = 1;
        for (AgentTraceEvent event : events) {
            assertThat(event.getSeq()).isEqualTo(seq++);
        }
    }

    private List<String> types(String sessionId) {
        return eventMapper.selectExportBatch(sessionId, 0L, 1000)
                .stream().map(AgentTraceEvent::getEventType).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
