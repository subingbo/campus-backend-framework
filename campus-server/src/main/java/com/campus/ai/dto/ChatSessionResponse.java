package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 会话列表项 */
@Data
public class ChatSessionResponse {

    private Long id;
    private String title;
    /** CHATTING / DRAFT / SAVED */
    private String status;
    private Integer planVersion;
    private Long savedActivityId;
    private LocalDateTime updatedAt;
}
