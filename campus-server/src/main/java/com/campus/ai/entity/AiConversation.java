package com.campus.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 规划会话；plan_json 是最近一次校验通过的草稿，SAVED 后 saved_activity_id 保证 save 幂等 */
@Data
public class AiConversation {

    public static final String STATUS_CHATTING = "CHATTING";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SAVED = "SAVED";

    private Long id;
    private Long userId;
    private String title;
    private String status;
    private String planJson;
    private Integer planVersion;
    private Long savedActivityId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
