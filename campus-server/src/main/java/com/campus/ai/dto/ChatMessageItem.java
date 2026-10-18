package com.campus.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageItem {

    private Long id;
    /** USER / ASSISTANT */
    private String role;
    private String content;
    /** 助手消息附带的地理事件 JSON（就近地图回放）；没有则为 null */
    private String geoJson;
    private LocalDateTime createdAt;
}
