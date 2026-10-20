package com.campus.ai.trace.store;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.dto.TraceEventRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 有限容量事件队列 + 单消费者落库。
 *
 * <p>设计原则（计划 §7）：
 * <ul>
 *   <li>业务线程只做脱敏 + 入队，最多在关键事件且队列满时同步 fallback；</li>
 *   <li>单消费者保证同一 session 的持久化顺序，seq 仍由 DB 行锁分配；</li>
 *   <li>单条失败不杀死消费者，只累计 dropped_count 并限频告警；</li>
 *   <li>关闭时做有上限 drain，未落库事件可观测。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class AgentTraceWriter {

    private static final long RETRY_OFFER_MILLIS = 20L;
    private static final long DROP_FLUSH_INTERVAL_MILLIS = 1000L;

    private final TraceEventStore store;
    private final AgentTraceProperties properties;
    private final BlockingQueue<Object> queue;
    private final int batchSize;
    private final long flushTimeoutMs;
    private final long criticalOfferTimeoutMs;
    private final ConcurrentHashMap<String, Long> droppedCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> pendingCounts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong failureCount = new AtomicLong();

    private volatile Thread worker;

    public AgentTraceWriter(TraceEventStore store, AgentTraceProperties properties) {
        this.store = store;
        this.properties = properties;
        int capacity = properties.getWriter().getQueueCapacity();
        this.queue = new ArrayBlockingQueue<>(Math.min(100_000, Math.max(16, capacity)));
        this.batchSize = Math.min(500, Math.max(1, properties.getWriter().getBatchSize()));
        this.flushTimeoutMs = Math.max(200L, properties.getWriter().getFlushTimeoutMs());
        this.criticalOfferTimeoutMs = Math.max(50L,
                properties.getWriter().getCriticalOfferTimeoutMs());
    }

    @PostConstruct
    public void start() {
        if (!properties.isTraceActive() || !running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::consume, "agent-trace-writer");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
    }

    @PreDestroy
    public void shutdown() {
        if (!running.getAndSet(false)) {
            return;
        }
        Thread thread = worker;
        if (thread != null) {
            try {
                thread.join(Math.min(flushTimeoutMs, 5000L));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
        flushDroppedCounters();
    }

    /** 业务线程调用；永不抛出 */
    public void enqueue(TraceEventRecord record) {
        if (record == null || record.getSessionId() == null || record.getSessionId().isBlank()
                || !running.get()) {
            return;
        }
        String sessionId = record.getSessionId();
        // 先计数再入队，避免消费者先 decrement 造成 phantom pending
        incrementPending(sessionId);
        if (queue.offer(record)) {
            return;
        }
        decrementPending(sessionId);

        if (record.isCritical()) {
            // 关键事件尽量排在已入队事件之后；有界等待，避免无限阻塞业务线程
            long deadline = System.currentTimeMillis() + criticalOfferTimeoutMs;
            while (System.currentTimeMillis() < deadline) {
                incrementPending(sessionId);
                boolean offered = false;
                try {
                    offered = queue.offer(record, RETRY_OFFER_MILLIS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                if (offered) {
                    return;
                }
                decrementPending(sessionId);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            log.warn("Trace 队列持续满，关键事件同步落库可能造成 seq 顺序偏移 "
                    + "type={} session={} timeoutMs={}", record.getType(), sessionId,
                    criticalOfferTimeoutMs);
            try {
                store.persist(record);
                return;
            } catch (TraceSessionMissingException ex) {
                log.debug("Trace session 已删除，跳过关键事件 {}", sessionId);
                return;
            } catch (Throwable ex) {
                warnFailure(record.getType(), sessionId, ex);
            }
        }
        recordDrop(sessionId);
    }

    /** 等待队列中此前的事件全部落库；测试与优雅关闭使用 */
    public boolean flushNow() {
        if (!running.get()) {
            return true;
        }
        FlushBarrier barrier = new FlushBarrier();
        long deadline = System.currentTimeMillis() + flushTimeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (queue.offer(barrier, RETRY_OFFER_MILLIS, TimeUnit.MILLISECONDS)) {
                    boolean done = barrier.latch.await(Math.max(1L, deadline - System.currentTimeMillis()),
                            TimeUnit.MILLISECONDS);
                    flushDroppedCounters();
                    return done;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        flushDroppedCounters();
        return false;
    }

    /** 供测试/诊断：当前尚未落库的丢弃计数快照 */
    public Map<String, Long> droppedSnapshot() {
        return new ConcurrentHashMap<>(droppedCounts);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void consume() {
        long lastDropFlush = System.currentTimeMillis();
        while (running.get() || !queue.isEmpty()) {
            try {
                Object first = queue.poll(200L, TimeUnit.MILLISECONDS);
                if (first != null) {
                    List<Object> batch = new ArrayList<>(batchSize);
                    batch.add(first);
                    queue.drainTo(batch, batchSize - 1);
                    for (Object item : batch) {
                        process(item);
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastDropFlush >= DROP_FLUSH_INTERVAL_MILLIS) {
                    flushDroppedCounters();
                    lastDropFlush = now;
                }
            } catch (InterruptedException ex) {
                if (!running.get() && queue.isEmpty()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Throwable ex) {
                log.warn("Trace writer 循环异常（继续消费）：{}", ex.getClass().getSimpleName());
            }
        }
    }

    private void process(Object item) {
        if (item instanceof FlushBarrier barrier) {
            flushDroppedCounters();
            barrier.latch.countDown();
            return;
        }
        if (!(item instanceof TraceEventRecord record)) {
            return;
        }
        try {
            store.persist(record);
        } catch (TraceSessionMissingException ex) {
            log.debug("Trace session 已删除，跳过事件 type={} session={}",
                    record.getType(), record.getSessionId());
        } catch (Throwable ex) {
            recordDrop(record.getSessionId());
            warnFailure(record.getType(), record.getSessionId(), ex);
        } finally {
            decrementPending(record.getSessionId());
        }
    }

    /** 会话级待落库事件数；终态会话只有在该值为 0 时才说明尾部真正同步完成 */
    public long pendingCount(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        Long count = pendingCounts.get(sessionId);
        return count == null ? 0L : Math.max(0L, count);
    }

    /** 尚未持久化为 trace/partial 的丢弃计数（单 JVM 内存态） */
    public long pendingDroppedCount(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0L;
        }
        Long count = droppedCounts.get(sessionId);
        return count == null ? 0L : Math.max(0L, count);
    }

    private void incrementPending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        pendingCounts.compute(sessionId, (key, count) -> count == null ? 1L : count + 1L);
    }

    private void decrementPending(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        pendingCounts.computeIfPresent(sessionId, (key, count) -> count <= 1L ? null : count - 1L);
    }

    private void flushDroppedCounters() {
        for (String sessionId : List.copyOf(droppedCounts.keySet())) {
            Long snapshot = droppedCounts.get(sessionId);
            if (snapshot == null || snapshot <= 0L) {
                droppedCounts.remove(sessionId, snapshot);
                continue;
            }
            try {
                store.persistPartial(sessionId, snapshot);
                // 只减去本次快照；并发期间新增的增量保留到下一轮
                droppedCounts.computeIfPresent(sessionId, (key, count) ->
                        count <= snapshot ? null : count - snapshot);
            } catch (TraceSessionMissingException ex) {
                droppedCounts.remove(sessionId);
            } catch (Throwable ex) {
                warnFailure("trace/partial", sessionId, ex);
            }
        }
    }

    private void recordDrop(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        droppedCounts.compute(sessionId, (key, count) -> count == null ? 1L : count + 1L);
    }

    private void warnFailure(String type, String sessionId, Throwable ex) {
        long count = failureCount.incrementAndGet();
        if (count <= 5 || count % 100 == 0) {
            log.warn("Trace 事件写入失败 type={} session={} failure={}：{}",
                    type, sessionId, count, ex.getClass().getSimpleName());
        }
    }

    private static final class FlushBarrier {
        private final CountDownLatch latch = new CountDownLatch(1);
    }
}
