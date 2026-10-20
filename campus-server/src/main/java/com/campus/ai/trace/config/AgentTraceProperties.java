package com.campus.ai.trace.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

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
    private Pricing pricing = new Pricing();

    /** 是否真正采集（enabled 且 level 不是 OFF） */
    public boolean isTraceActive() {
        return enabled && level != null && !"OFF".equalsIgnoreCase(level);
    }

    @Data
    public static class Writer {
        private int queueCapacity = 2000;
        private int batchSize = 50;
        private long flushTimeoutMs = 3000L;
        /** 队列满时，关键事件最多等待多久再回退同步落库（毫秒） */
        private long criticalOfferTimeoutMs = 500L;
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

    /**
     * 读取时成本估算配置。费率由部署方按真实账单维护，代码不硬编码价格。
     * 未知模型/缺失费率时接口返回不可估算，绝不填 0。
     */
    @Data
    public static class Pricing {
        private boolean enabled = true;
        private String currency = "CNY";
        private String version = "config";
        private Map<String, ModelPrice> models = new LinkedHashMap<>();
    }

    @Data
    public static class ModelPrice {
        /** 每百万输入 Token 单价（币种由 Pricing.currency 指定） */
        private BigDecimal inputPerMillion;
        /** 每百万输出 Token 单价 */
        private BigDecimal outputPerMillion;
    }
}
