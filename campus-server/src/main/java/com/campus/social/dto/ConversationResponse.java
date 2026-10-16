package com.campus.social.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationResponse {

    private Long id;
    private String type;
    private String title;
    private Long mapId;
    private Long placeId;
    private Long peerUserId;
    private String peerNickname;
    private ChatMessageResponse lastMessage;
    private long unread;
    private LocalDateTime createdAt;
}
