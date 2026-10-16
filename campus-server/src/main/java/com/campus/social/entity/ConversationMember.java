package com.campus.social.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationMember {

    private Long id;
    private Long conversationId;
    private Long userId;
    private LocalDateTime createdAt;
}
