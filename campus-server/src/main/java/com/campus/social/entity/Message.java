package com.campus.social.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Message {

    private Long id;
    private Long conversationId;
    private Long senderId;
    private String content;
    private String type;
    /** 客户端幂等ID：断线重试时服务端据此去重 */
    private String clientMsgId;
    private LocalDateTime createdAt;
}
