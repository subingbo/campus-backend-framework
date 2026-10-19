package com.campus.ai.trace.service;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.store.TraceEventStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 保留策略与陈旧会话清理。
 *
 * <p>RUNNING 超过 stale-running-minutes 的会话标 INTERRUPTED 并写 trace/partial(inferred=true)，
 * 不伪造 session/end；活动中的 run 会被跳过。明细超过 detail-days 后按批删除（事件级联删除）。</p>
 *
 * <p>多实例并发清理未做分布式锁（P0 单实例假设）；即使重复清理，finish 的
 * WHERE status='RUNNING' 也保证只有一次生效。</p>
 */
@Slf4j
@Component
public class AgentTraceCleanupJob {

    private static final int STALE_BATCH = 200;
    private static final int DELETE_BATCH = 500;
    private static final int MAX_STALE_ROUNDS = 20;
    private static final int MAX_DELETE_ROUNDS = 100;

    private final AgentTraceService traceService;
    private final TraceEventStore store;
    private final AgentTraceProperties properties;

    public AgentTraceCleanupJob(AgentTraceService traceService,
                                TraceEventStore store,
                                AgentTraceProperties properties) {
        this.traceService = traceService;
        this.store = store;
        this.properties = properties;
    }

    @Scheduled(cron = "${campus.agent.trace.retention.cleanup-cron:0 30 3 * * *}")
    public void scheduledCleanup() {
        markStaleRunning();
        deleteExpired();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 进程重启后，上次未正常结束的 RUNNING 会话应尽快可见，而不是等夜间任务
        markStaleRunning();
    }

    public int markStaleRunning() {
        if (!properties.isTraceActive()) {
            return 0;
        }
        int marked = 0;
        try {
            int staleMinutes = Math.max(1, properties.getRetention().getStaleRunningMinutes());
            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(staleMinutes);
            for (int round = 0; round < MAX_STALE_ROUNDS; round++) {
                List<String> ids = store.findStaleRunning(cutoff, STALE_BATCH);
                if (ids.isEmpty()) {
                    break;
                }
                for (String sessionId : ids) {
                    if (traceService.isRunActive(sessionId)) {
                        continue;
                    }
                    try {
                        store.markInterrupted(sessionId);
                        marked++;
                    } catch (Exception ex) {
                        log.warn("Trace 陈旧会话标记失败 session={}：{}",
                                sessionId, ex.getClass().getSimpleName());
                    }
                }
                if (ids.size() < STALE_BATCH) {
                    break;
                }
            }
            if (marked > 0) {
                log.info("Agent Trace 陈旧 RUNNING 会话已标记 INTERRUPTED：{} 个", marked);
            }
        } catch (Exception ex) {
            log.warn("Agent Trace 陈旧会话清理暂不可用（业务继续）：{}；请确认已执行 Trace 迁移",
                    ex.getClass().getSimpleName());
        }
        return marked;
    }

    public int deleteExpired() {
        if (!properties.isTraceActive()) {
            return 0;
        }
        int deleted = 0;
        try {
            int detailDays = Math.max(1, properties.getRetention().getDetailDays());
            LocalDateTime cutoff = LocalDateTime.now().minusDays(detailDays);
            for (int round = 0; round < MAX_DELETE_ROUNDS; round++) {
                int batch = store.deleteOlderThan(cutoff, DELETE_BATCH);
                deleted += batch;
                if (batch < DELETE_BATCH) {
                    break;
                }
            }
            if (deleted > 0) {
                log.info("Agent Trace 过期明细已删除：{} 个会话（{} 天前）", deleted, detailDays);
            }
        } catch (Exception ex) {
            log.warn("Agent Trace 过期清理暂不可用（业务继续）：{}；请确认已执行 Trace 迁移",
                    ex.getClass().getSimpleName());
        }
        return deleted;
    }
}
