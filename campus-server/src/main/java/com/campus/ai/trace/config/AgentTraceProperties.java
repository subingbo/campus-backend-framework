package com.campus.ai.trace.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent Trace 配置（计划 §8.4，前缀按本工程既有约定改为 campus.agent.trace）。
 * DEBUG 在 P0 与 SUMMARY 采集范围相同：绝不因为打开 DEBUG 就记录聊天原文/坐标/密钥。
 */
@Data
@Component
@ConfigurationProperties(prefix = "campus.agent.trace")
public class AgentTraceProperties {

    /** 总开关；关闭后所有 Trace 调用退化为 no-op，业务完全不受影响 */
    private boolean enabled = true;

    /** OFF / SUMMARY / DEBUG；OFF 等价于关闭 */
    private String level = "SUMMARY";

    /** 单事件 payload 上限（字节），超限只保留截断标记 */
    private int maxPayloadBytes = 16 * 1024;

    private Writer writer = new Writer();
    private Retention retention = new Retention();
    private Export export = new Export();

    /** 是否真正采集（enabled 且 level 不是 OFF） */
    public boolean isTraceActive() {
        return enabled && level != null && !"OFF".equalsIgnoreCase(level);
    }

    @Data
    public static class Writer {
        private int queueCapacity = 2000;
        private int batchSize = 50;
        private long flushTimeoutMs = 3000L;
    }

    @Data
    public static class Retention {
        private int detailDays = 30;
        private int staleRunningMinutes = 120;
        private String cleanupCron = "0 30 3 * * *";
    }

    @Data
    public static class Export {
        private boolean enabled = true;
        private int pageSize = 500;
    }
}
