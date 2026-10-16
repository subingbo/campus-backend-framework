package com.campus.social.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageResponse {

    private Long id;
    private Long conversationId;
    private Long senderId;
    private String senderNickname;
    private String content;
    private String type;
    private LocalDateTime createdAt;
}
