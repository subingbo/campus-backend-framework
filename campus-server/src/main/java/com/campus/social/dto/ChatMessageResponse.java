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
    /** 客户端幂等ID：发送方据此匹配 ACK、去重待发送队列 */
    private String clientMsgId;
    private LocalDateTime createdAt;
}
