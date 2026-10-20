package com.campus.ai;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.store.AgentTraceWriter;
import com.campus.ai.trace.store.TraceEventStore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 队列满/落库失败/关闭 drain：Trace 写入绝不阻塞或杀死业务。 */
class AgentTraceWriterTest {

    private AgentTraceProperties props(int capacity) {
        AgentTraceProperties properties = new AgentTraceProperties();
        properties.setEnabled(true);
        properties.getWriter().setQueueCapacity(capacity);
        properties.getWriter().setBatchSize(1);
        properties.getWriter().setFlushTimeoutMs(3000);
        return properties;
    }

    private TraceEventRecord event(String sessionId, String type, boolean critical) {
        return TraceEventRecord.builder()
                .sessionId(sessionId)
                .traceId("trace-" + sessionId)
                .occurredAtMs(System.currentTimeMillis())
                .type(type)
                .status("SUCCESS")
                .data(java.util.Map.of("round", 1))
                .critical(critical)
                .build();
    }

    @Test
    void queueFullDropsNonCriticalAndCounts() throws Exception {
        FakeStore store = new FakeStore();
        store.block = new CountDownLatch(1);
        AgentTraceWriter writer = new AgentTraceWriter(store, props(2));
        writer.start();
        try {
            // 第一条被消费者取走后阻塞在 store；队列容量 2，后续事件开始丢弃
            writer.enqueue(event("s1", "tool/result", false));
            Thread.sleep(100);
            for (int i = 0; i < 20; i++) {
                writer.enqueue(event("s1", "tool/result", false));
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline
                    && writer.droppedSnapshot().getOrDefault("s1", 0L) == 0L) {
                Thread.sleep(20);
            }
            assertThat(writer.droppedSnapshot().getOrDefault("s1", 0L)).isPositive();

            store.block.countDown();
            assertThat(writer.flushNow()).isTrue();
            assertThat(store.persisted).isNotEmpty();
            assertThat(store.partialCounts).isNotEmpty();
            assertThat(writer.pendingCount("s1")).isZero();
            assertThat(writer.droppedSnapshot()).isEmpty();
        } finally {
            writer.shutdown();
        }
    }

    @Test
    void criticalEventWaitsBehindEarlierQueuedEvents() throws Exception {
        FakeStore store = new FakeStore();
        store.blockFirst = new CountDownLatch(1);
        AgentTraceProperties properties = props(2);
        properties.getWriter().setCriticalOfferTimeoutMs(2000);
        AgentTraceWriter writer = new AgentTraceWriter(store, properties);
        writer.start();
        try {
            writer.enqueue(event("s1", "llm/request", false));
            assertThat(store.firstPersistStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 16; i++) {
                writer.enqueue(event("s1", "tool/result", false));
            }

            Thread critical = new Thread(
                    () -> writer.enqueue(event("s1", "session/end", true)));
            critical.start();
            Thread.sleep(100);
            assertThat(store.persisted).isEmpty();
            assertThat(writer.pendingCount("s1")).isPositive();

            store.blockFirst.countDown();
            critical.join(3000);
            assertThat(writer.flushNow()).isTrue();
            assertThat(store.persisted).hasSize(18);
            assertThat(store.persisted.get(0).getType()).isEqualTo("llm/request");
            assertThat(store.persisted.get(17).getType()).isEqualTo("session/end");
            assertThat(writer.pendingCount("s1")).isZero();
        } finally {
            store.blockFirst.countDown();
            writer.shutdown();
        }
    }

    @Test
    void criticalEventFallsBackOnlyAfterBoundedWait() throws Exception {
        FakeStore store = new FakeStore();
        store.blockFirst = new CountDownLatch(1);
        AgentTraceProperties properties = props(1);
        properties.getWriter().setCriticalOfferTimeoutMs(100);
        AgentTraceWriter writer = new AgentTraceWriter(store, properties);
        writer.start();
        try {
            writer.enqueue(event("s1", "llm/request", false));
            assertThat(store.firstPersistStarted.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 16; i++) {
                writer.enqueue(event("s1", "tool/result", false));
            }

            writer.enqueue(event("s1", "session/end", true));
            // 前一条仍在阻塞；关键事件等待超时后同步落库
            assertThat(store.persisted)
                    .extracting(TraceEventRecord::getType)
                    .contains("session/end");

            store.blockFirst.countDown();
            assertThat(writer.flushNow()).isTrue();
            assertThat(writer.pendingCount("s1")).isZero();
        } finally {
            store.blockFirst.countDown();
            writer.shutdown();
        }
    }

    @Test
    void storeFailureDoesNotKillConsumer() throws Exception {
        FakeStore store = new FakeStore();
        store.failNext = true;
        AgentTraceWriter writer = new AgentTraceWriter(store, props(10));
        writer.start();
        try {
            writer.enqueue(event("s2", "tool/call", false));
            Thread.sleep(100);
            writer.enqueue(event("s2", "tool/result", false));
            assertThat(writer.flushNow()).isTrue();
            assertThat(writer.isRunning()).isTrue();
            assertThat(store.persisted).hasSize(1);
            assertThat(store.persisted.get(0).getType()).isEqualTo("tool/result");
        } finally {
            writer.shutdown();
        }
    }

    @Test
    void flushWaitsForAllPriorEvents() {
        FakeStore store = new FakeStore();
        AgentTraceWriter writer = new AgentTraceWriter(store, props(100));
        writer.start();
        try {
            for (int i = 0; i < 5; i++) {
                writer.enqueue(event("s3", "step/end", false));
            }
            assertThat(writer.flushNow()).isTrue();
            assertThat(store.persisted).hasSize(5);
            assertThat(writer.pendingCount("s3")).isZero();
        } finally {
            writer.shutdown();
        }
    }

    private static final class FakeStore implements TraceEventStore {

        private final List<TraceEventRecord> persisted = new CopyOnWriteArrayList<>();
        private final List<Long> partialCounts = new CopyOnWriteArrayList<>();
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch firstPersistStarted = new CountDownLatch(1);
        private volatile CountDownLatch block;
        private volatile CountDownLatch blockFirst;
        private volatile boolean failNext;

        @Override
        public void createSession(AgentTraceSession session) {
        }

        @Override
        public void persist(TraceEventRecord record) {
            CountDownLatch latch = block;
            if (latch != null) {
                try {
                    latch.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            CountDownLatch firstLatch = blockFirst;
            if (firstLatch != null && first.compareAndSet(true, false)) {
                firstPersistStarted.countDown();
                try {
                    firstLatch.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failNext) {
                failNext = false;
                throw new RuntimeException("injected trace store failure");
            }
            persisted.add(record);
        }

        @Override
        public void persistPartial(String sessionId, long droppedCount) {
            partialCounts.add(droppedCount);
        }

        @Override
        public void markInterrupted(String sessionId) {
        }

        @Override
        public List<String> findStaleRunning(LocalDateTime cutoff, int limit) {
            return List.of();
        }

        @Override
        public int deleteOlderThan(LocalDateTime cutoff, int limit) {
            return 0;
        }

        @Override
        public void bindDraft(String sessionId, Long draftId) {
        }

        @Override
        public void bindContext(String sessionId, Long sharedMapId, Long groupId) {
        }
    }
}
