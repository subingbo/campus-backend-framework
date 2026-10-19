package com.campus.ai.trace.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/** 事件列表/导出用的视图；data 是已脱敏 payload_json 解析结果 */
@Data
public class TraceEventView {

    private Long eventId;
    private String type;
    private Long seq;
    private Long time;
    private Integer turn;
    private Integer step;
    private String callId;
    private Long parentEventId;
    private String status;
    private Long durationMs;
    private String tool;
    private String errorCode;
    private JsonNode data;
}
