package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageItem {

    private Long id;
    /** USER / ASSISTANT */
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
