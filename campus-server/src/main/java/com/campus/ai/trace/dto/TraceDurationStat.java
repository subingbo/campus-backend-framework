package com.campus.ai.trace.dto;

import lombok.Data;

/** 详情页耗时统计行：按事件类型聚合，tool/result 再按工具名细分 */
@Data
public class TraceDurationStat {

    private String type;
    /** 仅 tool/result 有值 */
    private String tool;
    private int count;
    private long totalMs;
    private long avgMs;
    private long p50Ms;
    private long p95Ms;
}
