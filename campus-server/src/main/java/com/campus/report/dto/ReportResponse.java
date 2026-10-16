package com.campus.report.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReportResponse {

    private Long id;
    private Long reporterId;
    private String targetType;
    private Long targetId;
    private String reason;
    private Integer status;
    private Long handlerId;
    private String handleAction;
    private LocalDateTime createdAt;
    private LocalDateTime handledAt;
}
