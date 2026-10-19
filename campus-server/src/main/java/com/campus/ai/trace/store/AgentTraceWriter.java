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
    private final ConcurrentHashMap<String, AtomicLong> droppedCounts = new ConcurrentHashMap<>();
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
        if (queue.offer(record)) {
            return;
        }
        if (record.isCritical()) {
            try {
                store.persist(record);
                return;
            } catch (TraceSessionMissingException ex) {
                log.debug("Trace session 已删除，跳过关键事件 {}", record.getSessionId());
                return;
            } catch (Throwable ex) {
                warnFailure(record.getType(), record.getSessionId(), ex);
            }
        }
        recordDrop(record.getSessionId());
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
        Map<String, Long> snapshot = new ConcurrentHashMap<>();
        droppedCounts.forEach((sessionId, count) -> snapshot.put(sessionId, count.get()));
        return snapshot;
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
        }
    }

    private void flushDroppedCounters() {
        for (Map.Entry<String, AtomicLong> entry : droppedCounts.entrySet()) {
            String sessionId = entry.getKey();
            long count = entry.getValue().get();
            if (count <= 0) {
                droppedCounts.remove(sessionId, entry.getValue());
                continue;
            }
            try {
                store.persistPartial(sessionId, count);
                if (entry.getValue().addAndGet(-count) <= 0) {
                    droppedCounts.remove(sessionId, entry.getValue());
                }
            } catch (TraceSessionMissingException ex) {
                droppedCounts.remove(sessionId, entry.getValue());
            } catch (Throwable ex) {
                warnFailure("trace/partial", sessionId, ex);
            }
        }
    }

    private void recordDrop(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        droppedCounts.computeIfAbsent(sessionId, key -> new AtomicLong()).incrementAndGet();
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
