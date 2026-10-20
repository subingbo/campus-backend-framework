package com.campus.ai.trace.dto;

import lombok.Data;

/** 一次 Trace 会话的 LLM usage 完整性摘要（按 llm/response 事件的脱敏 payload 聚合） */
@Data
public class TraceLlmUsageStat {

    private String sessionId;
    /** llm/response 事件数 */
    private Long responseCount;
    /** 缺 prompt/completion token 的响应数 */
    private Long missingUsageCount;
    /** 缺 model 字段的响应数 */
    private Long missingModelCount;
    /** 不同 model 的数量 */
    private Long modelCount;
    /** 聚合出的模型名（仅 modelCount=1 时有意义） */
    private String model;
}
