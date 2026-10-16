package com.campus.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 规划会话消息（只存用户消息与助手最终答复，工具过程不落库） */
@Data
public class AiMessage {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ASSISTANT = "ASSISTANT";

    private Long id;
    private Long conversationId;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
